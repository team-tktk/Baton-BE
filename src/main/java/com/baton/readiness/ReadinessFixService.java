package com.baton.readiness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baton.ai.ClarificationQuestion;
import com.baton.ai.ClarificationQuestionRepository;
import com.baton.ai.ClarificationQuestionStatus;
import com.baton.ai.DraftSection;
import com.baton.ai.HandoverDraft;
import com.baton.ai.HandoverDraftRepository;
import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentRepository;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.ai.dto.HandoverDraftResponse;
import com.baton.ai.dto.QuestionOption;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.readiness.dto.ApplyFixResponse;
import com.baton.readiness.dto.FixAnswerRequest;
import com.baton.readiness.dto.GeneratedAreaFix;
import com.baton.readiness.dto.GeneratedFix;
import com.baton.readiness.dto.GeneratedItemQuestion;
import com.baton.readiness.dto.ReadinessFixResponse;
import com.baton.readiness.dto.ReadinessResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부족 영역 여러 개를 한 번에 보완한다.
 * 시작(평가가 만든 질문 + 미룬 확인 질문을 모음, AI 호출 없음) → 답변 저장(AI 호출 없음)
 * → 보완안 만들기(자료 검색 + AI 1회, 사용량 1회 차감) → 수정 전후 비교 → 적용 → 바뀐 영역만 재평가.
 * 사용자가 적용하기 전에는 문서를 건드리지 않고, 적용할 때도 수정안이 있는 영역의 섹션만 바꾼다.
 * 보완을 시작할 때 본 문서 버전과 적용 시점의 버전이 다르면 적용을 거절해 그사이의 수정을 덮어쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadinessFixService {

	private static final int TOP_K_PER_AREA = 4;
	private static final int MAX_EXCERPTS = 12;
	private static final double SIMILARITY_THRESHOLD = 0.3;
	private static final int MAX_QUESTIONS_PER_AREA = 3;
	private static final String FALLBACK_QUESTION = "%s을(를) 채우려면 문서에 무엇을 적으면 좋을지 알려주세요.";
	private static final String FALLBACK_QUESTION_AGAIN = "%s에 꼭 들어가야 할 내용을 직접 적어주세요.";
	private static final String CONFLICT_QUESTION = "자료마다 다르게 적힌 %s 기준 중 어느 쪽이 맞나요?";

	private final ReadinessService readinessService;
	private final ReadinessFixRepository fixRepository;
	private final ReadinessEvaluationRepository evaluationRepository;
	private final HandoverDraftRepository handoverDraftRepository;
	private final SourceDocumentRepository sourceDocumentRepository;
	private final ClarificationQuestionRepository clarificationQuestionRepository;
	private final VectorStore vectorStore;
	private final ChatClient chatClient;
	private final TransactionTemplate transactionTemplate;

	@Value("${app.ai.analysis-model:gpt-5.4}")
	private String analysisModel;

	/** 자료 검색으로 찾은 발췌 한 건. number는 프롬프트에서 AI가 근거로 가리키는 번호. */
	record Excerpt(int number, UUID sourceId, String fileName, String locator, String text) {
	}

	/** 보완안 만들기에 필요한 값(트랜잭션 안에서 모아 둔다). */
	private record GenerateContext(ReadinessFix fix, Map<ReadinessArea, ReadinessItem> items, HandoverDraftContent content) {
	}

	/**
	 * 현재 평가의 부족 영역들로 보완을 시작한다. AI를 부르지 않는다(사용량 차감 없음).
	 * 질문은 평가가 만든 질문과, 확인 질문 단계에서 "나중에 답하기"로 미룬 같은 영역의 질문이다.
	 */
	public ReadinessFixResponse create(UUID handoverId, List<ReadinessArea> requested) {
		List<ReadinessArea> areas = requested.stream().distinct().toList();
		return transactionTemplate.execute(status -> {
			ReadinessEvaluation evaluation = readinessService.requireCurrent(handoverId);
			List<ReadinessItem> items = new ArrayList<>();
			for (ReadinessArea area : areas) {
				ReadinessItem item = evaluation.item(area)
						.orElseThrow(() -> new BusinessException(ErrorCode.READINESS_STALE));
				if (item.status() == ReadinessStatus.SUFFICIENT) {
					throw new BusinessException(ErrorCode.READINESS_ITEM_SUFFICIENT,
							"이미 충분한 항목은 보완할 필요가 없습니다: " + area.getLabel());
				}
				items.add(item);
			}
			HandoverDraft draft = loadDraft(handoverId);
			List<FixQuestion> questions = initialQuestions(items, readinessService.deferredClarificationQuestions(handoverId));
			ReadinessFix fix = fixRepository.save(ReadinessFix.open(handoverId, evaluation.getId(), items, questions,
					draft.getRevision(), draft.getContent()));
			return ReadinessFixResponse.of(fix, draft.getRevision());
		});
	}

	/** 영역마다 평가 질문 → 미룬 확인 질문 순. 같은 문장은 한 번만. */
	static List<FixQuestion> initialQuestions(List<ReadinessItem> items, List<ClarificationQuestion> deferred) {
		List<FixQuestion> questions = new ArrayList<>();
		for (ReadinessItem item : items) {
			Set<String> asked = new HashSet<>();
			for (ItemQuestion question : item.questionList()) {
				if (question.question() != null && asked.add(question.question().strip())) {
					questions.add(new FixQuestion(null, item.area(), question.question(), question.reason(),
							question.options() == null ? List.of() : question.options(), null, null));
				}
			}
			for (ClarificationQuestion question : deferred) {
				if (ReadinessService.areaOf(question) == item.area() && asked.add(question.getQuestionText().strip())) {
					questions.add(new FixQuestion(null, item.area(), question.getQuestionText(), question.getReason(),
							question.getOptions().stream().map(QuestionOption::label).filter(Objects::nonNull).toList(),
							question.getId(), null));
				}
			}
		}
		return questions;
	}

	public ReadinessFixResponse get(UUID handoverId, UUID fixId) {
		return transactionTemplate.execute(status -> {
			ReadinessFix fix = loadFix(handoverId, fixId);
			return ReadinessFixResponse.of(fix, currentRevision(handoverId));
		});
	}

	/** 질문에 답한다. 답만 저장하고 AI는 부르지 않는다 — 수정안은 보완안 만들기(generate)에서 한 번에 만든다. */
	public ReadinessFixResponse answer(UUID handoverId, UUID fixId, FixAnswerRequest request) {
		return transactionTemplate.execute(status -> {
			ReadinessFix fix = loadFix(handoverId, fixId);
			long revision = currentRevision(handoverId);
			requireNotStale(fix, revision);
			request.answers().forEach(answer -> fix.answer(answer.questionId(), answer.answer().strip()));
			return ReadinessFixResponse.of(fix, revision);
		});
	}

	/**
	 * 모든 영역의 수정안을 한 번에 만든다(AI 1회). beforeAiCall은 요청 검증을 모두 통과한 뒤 AI 호출 직전에 부른다(사용량 차감).
	 * 수정안이 나온 영역은 PROPOSED로 적용할 수 있고, 아직 부족한 영역은 새 질문이 붙는다.
	 */
	public ReadinessFixResponse generate(UUID handoverId, UUID fixId, Runnable beforeAiCall) {
		GenerateContext context = transactionTemplate.execute(status -> {
			ReadinessFix fix = loadFix(handoverId, fixId);
			requireOpen(fix);
			HandoverDraft draft = loadDraft(handoverId);
			requireNotStale(fix, draft.getRevision());
			ReadinessEvaluation evaluation = evaluationRepository.findById(fix.getEvaluationId())
					.orElseThrow(() -> new BusinessException(ErrorCode.READINESS_STALE));
			Map<ReadinessArea, ReadinessItem> items = new LinkedHashMap<>();
			for (FixAreaResult result : fix.getAreaResults()) {
				items.put(result.area(), evaluation.item(result.area())
						.orElseThrow(() -> new BusinessException(ErrorCode.READINESS_STALE)));
			}
			return new GenerateContext(fix, items, draft.getContent());
		});

		beforeAiCall.run();
		GeneratedResult generated = generate(context);

		return transactionTemplate.execute(status -> {
			// 생성 중에 들어온 답변을 잃지 않도록 다시 읽은 보완안에 결과를 붙인다.
			ReadinessFix current = loadFix(handoverId, fixId);
			requireOpen(current);
			long revision = currentRevision(handoverId);
			requireNotStale(current, revision);
			applyGeneration(current, context.content(), generated);
			return ReadinessFixResponse.of(current, revision);
		});
	}

	/**
	 * 사용자가 확인한 수정안을 문서에 적용한다. 수정안이 있는 영역의 섹션만 바꾸고,
	 * 적용 뒤 바뀐 섹션이 걸린 영역만 다시 평가한다(나머지 영역은 직전 평가 그대로).
	 * 미룬 확인 질문에 답했다면 그 질문도 답변·반영 완료로 바꾼다(확인 질문 반영 API가 같은 답을 다시 반영하지 않게).
	 * 재평가가 실패해도 적용은 되돌리지 않는다(응답의 readiness만 null).
	 */
	public ApplyFixResponse apply(UUID handoverId, UUID fixId, long baseRevision) {
		record Applied(ReadinessFixResponse fix, HandoverDraftResponse document, UUID evaluationId,
				Set<ReadinessArea> affected) {
		}
		Applied applied = transactionTemplate.execute(status -> {
			ReadinessFix fix = loadFix(handoverId, fixId);
			if (fix.getStatus() != ReadinessFixStatus.PROPOSED) {
				throw new BusinessException(ErrorCode.READINESS_FIX_INVALID_STATE, "확인할 수정안이 있는 보완안만 적용할 수 있습니다.");
			}
			HandoverDraft draft = handoverDraftRepository.findByHandoverIdForUpdate(handoverId)
					.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
			if (baseRevision != draft.getRevision()) {
				throw new BusinessException(ErrorCode.AI_DRAFT_REVISION_CONFLICT);
			}
			requireNotStale(fix, draft.getRevision());

			List<DraftSection> sections = fix.proposedSections();
			draft.replaceContent(DraftSection.merge(draft.getContent(), fix.getAfter(), sections));
			fix.markApplied(draft.getRevision());
			resolveDeferredQuestions(fix);
			handoverDraftRepository.flush();
			return new Applied(ReadinessFixResponse.of(fix, draft.getRevision()), HandoverDraftResponse.from(draft),
					fix.getEvaluationId(), affectedAreas(fix, sections));
		});

		ReadinessResponse readiness = null;
		try {
			readiness = readinessService.reevaluate(handoverId, applied.evaluationId(), applied.affected());
		} catch (RuntimeException e) {
			log.warn("[*] Re-evaluation after applying readiness fix failed: handoverId={}, fixId={}", handoverId, fixId, e);
		}
		return new ApplyFixResponse(applied.fix(), applied.document(), readiness);
	}

	/** 다시 평가할 영역: 수정안이 있는 영역 + 바뀐 섹션을 평가 대상으로 가진 영역. */
	static Set<ReadinessArea> affectedAreas(ReadinessFix fix, List<DraftSection> changedSections) {
		Set<ReadinessArea> areas = EnumSet.noneOf(ReadinessArea.class);
		fix.getAreaResults().stream().filter(FixAreaResult::proposed).forEach(result -> areas.add(result.area()));
		for (ReadinessArea area : ReadinessArea.values()) {
			if (area.getSections().stream().anyMatch(changedSections::contains)) {
				areas.add(area);
			}
		}
		return areas;
	}

	/** 보완안 적용으로 문서에 반영된 미룬 확인 질문(수정안이 있는 영역만)을 답변·반영 완료로 바꾼다. */
	private void resolveDeferredQuestions(ReadinessFix fix) {
		Set<ReadinessArea> proposed = fix.getAreaResults().stream()
				.filter(FixAreaResult::proposed)
				.map(FixAreaResult::area)
				.collect(Collectors.toSet());
		Instant now = Instant.now();
		for (FixQuestion question : fix.answeredQuestions()) {
			if (question.clarificationQuestionId() == null || !proposed.contains(question.area())) {
				continue;
			}
			clarificationQuestionRepository.findById(question.clarificationQuestionId())
					.filter(clarification -> clarification.getHandoverId().equals(fix.getHandoverId()))
					.filter(clarification -> clarification.getStatus() == ClarificationQuestionStatus.DEFERRED)
					.ifPresent(clarification -> {
						clarification.answer(question.answer());
						clarification.markApplied(now);
					});
		}
	}

	/** 보완안을 버린다(직접 수정하기를 골랐을 때 등). 문서는 바뀌지 않는다. */
	public ReadinessFixResponse discard(UUID handoverId, UUID fixId) {
		return transactionTemplate.execute(status -> {
			ReadinessFix fix = loadFix(handoverId, fixId);
			fix.discard();
			return ReadinessFixResponse.of(fix, currentRevision(handoverId));
		});
	}

	/**
	 * AI 결과를 영역별 결과로 옮긴다. 영역의 수정안은 다음을 모두 만족할 때만 인정한다:
	 * AI가 해결했다고 했고, 그 영역의 섹션이 실제로 바뀌었고, 충돌 영역이면 인계자가 답했다(자료 중 하나를 AI가 고르지 않게).
	 * 인정되지 않은 영역은 수정안을 버리고 새 질문을 붙인다(물을 게 없으면 직접 적어 달라는 질문).
	 */
	void applyGeneration(ReadinessFix fix, HandoverDraftContent current, GeneratedResult result) {
		GeneratedFix generated = result.generated();
		HandoverDraftContent patch = generated == null ? null : generated.patch();
		Set<DraftSection> changed = EnumSet.noneOf(DraftSection.class);
		if (patch != null) {
			fix.getSections().stream()
					.filter(section -> !section.isEmpty(patch)
							&& !Objects.equals(section.valueOf(patch), section.valueOf(current)))
					.forEach(changed::add);
		}
		Map<ReadinessArea, GeneratedAreaFix> byArea = new LinkedHashMap<>();
		if (generated != null && generated.areas() != null) {
			generated.areas().stream()
					.filter(area -> area != null && area.area() != null)
					.forEach(area -> byArea.putIfAbsent(area.area(), area));
		}
		Set<ReadinessArea> answeredAreas = fix.answeredQuestions().stream()
				.map(FixQuestion::area)
				.collect(Collectors.toSet());
		Set<String> asked = fix.getQuestions().stream()
				.map(question -> question.question().strip())
				.collect(Collectors.toCollection(HashSet::new));

		List<FixAreaResult> results = new ArrayList<>();
		List<FixQuestion> newQuestions = new ArrayList<>();
		Set<DraftSection> applied = EnumSet.noneOf(DraftSection.class);
		for (FixAreaResult area : fix.getAreaResults()) {
			GeneratedAreaFix areaFix = byArea.get(area.area());
			boolean conflictUnanswered = area.status() == ReadinessStatus.CONFLICT && !answeredAreas.contains(area.area());
			boolean resolved = areaFix != null && areaFix.resolved() && !conflictUnanswered
					&& area.sections().stream().anyMatch(changed::contains);
			if (resolved) {
				results.add(area.propose(ReadinessText.plain(areaFix.changeSummary()),
						citedEvidence(areaFix.excerptNumbers(), result.excerpts())));
				area.sections().stream().filter(changed::contains).forEach(applied::add);
				continue;
			}
			results.add(area.unresolved());
			newQuestions.addAll(followUpQuestions(fix, area, areaFix, conflictUnanswered, asked));
		}
		HandoverDraftContent after = applied.isEmpty() ? current : DraftSection.merge(current, patch, applied);
		fix.recordGeneration(results, after, newQuestions);
	}

	/** 아직 부족한 영역에 새로 물을 질문. AI 질문이 없고 답할 질문도 남지 않았으면 기본 질문 하나. */
	private static List<FixQuestion> followUpQuestions(ReadinessFix fix, FixAreaResult area, GeneratedAreaFix areaFix,
			boolean conflictUnanswered, Set<String> asked) {
		List<FixQuestion> questions = new ArrayList<>();
		List<GeneratedItemQuestion> generated = areaFix == null || areaFix.questions() == null
				? List.of() : areaFix.questions();
		for (GeneratedItemQuestion question : generated) {
			String text = question == null ? null : ReadinessText.plain(question.question());
			if (text != null && questions.size() < MAX_QUESTIONS_PER_AREA && asked.add(text)) {
				List<String> options = question.options() == null ? List.of() : question.options().stream()
						.map(ReadinessText::plain).filter(Objects::nonNull).distinct().toList();
				questions.add(new FixQuestion(null, area.area(), text, ReadinessText.plain(question.reason()), options,
						null, null));
			}
		}
		boolean hasUnanswered = fix.getQuestions().stream()
				.anyMatch(question -> question.area() == area.area() && !question.hasAnswer());
		if (questions.isEmpty() && !hasUnanswered) {
			String label = area.area().getLabel();
			String text = conflictUnanswered ? CONFLICT_QUESTION.formatted(label) : FALLBACK_QUESTION.formatted(label);
			if (!asked.add(text)) {
				text = FALLBACK_QUESTION_AGAIN.formatted(label);
				asked.add(text);
			}
			questions.add(new FixQuestion(null, area.area(), text, null, List.of(), null, null));
		}
		return questions;
	}

	/** AI 출력과 그때 보여준 발췌를 함께 들고 다닌다(발췌 번호 → 근거 파일 변환용). */
	record GeneratedResult(GeneratedFix generated, List<Excerpt> excerpts) {
	}

	private GeneratedResult generate(GenerateContext context) {
		ReadinessFix fix = context.fix();
		ReadinessRubric rubric = ReadinessRubrics.CURRENT;
		List<Excerpt> excerpts = search(fix.getHandoverId(), context.items().values().stream()
				.map(item -> String.join(" ", item.area().getLabel(), rubric.criteria().get(item.area()),
						nullToEmpty(item.summary()), nullToEmpty(item.resolution())))
				.toList());

		SystemPromptTemplate template = new SystemPromptTemplate(ReadinessPrompts.FIX_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of(
				"areas", describeAreas(fix, context.items(), rubric),
				"currentValues", describeCurrentValues(fix.getSections(), context.content()),
				"draft", readinessService.writeJson(context.content()),
				"excerpts", describeExcerpts(excerpts),
				"answers", describeAnswers(fix)));
		try {
			GeneratedFix generated = chatClient.prompt()
					.options(OpenAiChatOptions.builder().model(analysisModel))
					.messages(List.of(systemMessage))
					.call()
					.entity(GeneratedFix.class);
			return new GeneratedResult(generated, excerpts);
		} catch (RuntimeException e) {
			log.error("[*] Readiness fix generation failed: handoverId={}, fixId={}", fix.getHandoverId(), fix.getId(), e);
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 보완안 생성 중 오류가 발생했습니다.");
		}
	}

	private static String describeAreas(ReadinessFix fix, Map<ReadinessArea, ReadinessItem> items, ReadinessRubric rubric) {
		return fix.getAreaResults().stream()
				.map(result -> {
					ReadinessItem item = items.get(result.area());
					return "- %s / %s / %s / %s / %s / %s / %s".formatted(
							result.area().name(), result.area().getLabel(), rubric.criteria().get(result.area()),
							item.status().name() + "(" + item.status().getLabel() + ")",
							nullToEmpty(item.summary()), nullToEmpty(item.resolution()),
							DraftSection.describe(result.sections()));
				})
				.collect(Collectors.joining("\n"));
	}

	private String describeCurrentValues(List<DraftSection> sections, HandoverDraftContent content) {
		return sections.stream()
				.map(section -> "- %s(%s): %s".formatted(section.getLabel(), section.getFieldName(),
						readinessService.writeJson(section.valueOf(content))))
				.collect(Collectors.joining("\n"));
	}

	private static String describeAnswers(ReadinessFix fix) {
		String answers = fix.getAreaResults().stream()
				.map(result -> {
					String qna = fix.answeredQuestions().stream()
							.filter(question -> question.area() == result.area())
							.map(question -> "Q: " + question.question() + "\nA: " + question.answer())
							.collect(Collectors.joining("\n"));
					return qna.isEmpty() ? null : "[" + result.area().getLabel() + "]\n" + qna;
				})
				.filter(Objects::nonNull)
				.collect(Collectors.joining("\n\n"));
		return answers.isEmpty() ? "(답변 없음)" : answers;
	}

	/** 영역마다 이 인수인계의 청크를 검색해 합친다(같은 청크는 한 번만). 파일명은 트랜잭션 안에서 읽는다(원문 @Lob이 함께 로딩되기 때문). */
	private List<Excerpt> search(UUID handoverId, List<String> queries) {
		Map<String, Document> unique = new LinkedHashMap<>();
		for (String query : queries) {
			SearchRequest request = SearchRequest.builder()
					.query(query)
					.topK(TOP_K_PER_AREA)
					.similarityThreshold(SIMILARITY_THRESHOLD)
					.filterExpression(new FilterExpressionBuilder().eq("handoverId", handoverId.toString()).build())
					.build();
			List<Document> matches = vectorStore.similaritySearch(request);
			if (matches != null) {
				matches.forEach(match -> unique.putIfAbsent(match.getId() != null ? match.getId() : match.getText(), match));
			}
		}
		if (unique.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> fileNames = transactionTemplate.execute(status ->
				sourceDocumentRepository.findAllByHandoverId(handoverId).stream()
						.collect(Collectors.toMap(SourceDocument::getId, SourceDocument::getFileName)));

		List<Excerpt> excerpts = new ArrayList<>();
		for (Document match : unique.values()) {
			Object rawId = match.getMetadata().get("sourceDocumentId");
			UUID sourceId = rawId == null ? null : UUID.fromString(rawId.toString());
			String fileName = sourceId == null ? null : fileNames.get(sourceId);
			if (fileName == null || excerpts.size() >= MAX_EXCERPTS) {
				continue;
			}
			excerpts.add(new Excerpt(excerpts.size() + 1, sourceId, fileName, locator(match), match.getText()));
		}
		return excerpts;
	}

	private static String locator(Document match) {
		Object chunkIndex = match.getMetadata().get("chunkIndex");
		Object totalChunks = match.getMetadata().get("total_chunks");
		if (chunkIndex == null || totalChunks == null) {
			return null;
		}
		return "청크 %d/%s".formatted(Integer.parseInt(chunkIndex.toString()) + 1, totalChunks);
	}

	private static String describeExcerpts(List<Excerpt> excerpts) {
		if (excerpts.isEmpty()) {
			return "(관련 자료를 찾지 못함)";
		}
		return excerpts.stream()
				.map(excerpt -> "[%d] %s / %s\n%s".formatted(excerpt.number(), excerpt.fileName(),
						excerpt.locator() == null ? "-" : excerpt.locator(), excerpt.text()))
				.collect(Collectors.joining("\n\n"));
	}

	private static List<ReadinessEvidence> citedEvidence(List<Integer> numbers, List<Excerpt> excerpts) {
		if (numbers == null || numbers.isEmpty()) {
			return List.of();
		}
		Map<Integer, Excerpt> byNumber = excerpts.stream()
				.collect(Collectors.toMap(Excerpt::number, Function.identity()));
		Map<String, ReadinessEvidence> unique = new LinkedHashMap<>();
		for (Integer number : numbers) {
			Excerpt excerpt = number == null ? null : byNumber.get(number);
			if (excerpt != null) {
				unique.putIfAbsent(excerpt.sourceId() + "|" + excerpt.locator(),
						new ReadinessEvidence(excerpt.sourceId(), excerpt.fileName(), excerpt.locator()));
			}
		}
		return List.copyOf(unique.values());
	}

	private ReadinessFix loadFix(UUID handoverId, UUID fixId) {
		return fixRepository.findByIdAndHandoverId(fixId, handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.READINESS_FIX_NOT_FOUND));
	}

	private HandoverDraft loadDraft(UUID handoverId) {
		return handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
	}

	private long currentRevision(UUID handoverId) {
		return loadDraft(handoverId).getRevision();
	}

	private static void requireOpen(ReadinessFix fix) {
		if (!fix.getStatus().isOpen()) {
			throw new BusinessException(ErrorCode.READINESS_FIX_INVALID_STATE, "이미 적용했거나 취소한 보완안입니다.");
		}
	}

	private static void requireNotStale(ReadinessFix fix, long currentRevision) {
		if (fix.getStatus().isOpen() && fix.isStaleAgainst(currentRevision)) {
			throw new BusinessException(ErrorCode.AI_DRAFT_REVISION_CONFLICT,
					"보완을 시작한 뒤 문서가 바뀌었습니다. 다시 평가한 뒤 보완을 새로 시작해주세요.");
		}
	}

	private static String nullToEmpty(String text) {
		return text == null ? "" : text;
	}
}
