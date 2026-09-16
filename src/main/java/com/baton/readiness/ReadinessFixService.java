package com.baton.readiness;

import java.util.ArrayList;
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

import com.baton.ai.DraftSection;
import com.baton.ai.HandoverDraft;
import com.baton.ai.HandoverDraftRepository;
import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentRepository;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.ai.dto.HandoverDraftResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.readiness.dto.ApplyFixResponse;
import com.baton.readiness.dto.FixAnswerRequest;
import com.baton.readiness.dto.GeneratedFix;
import com.baton.readiness.dto.GeneratedFixQuestion;
import com.baton.readiness.dto.ReadinessFixResponse;
import com.baton.readiness.dto.ReadinessResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부족 항목 하나를 보완한다: 자료 검색 → (사실이 있으면) 수정안 / (없으면) 추가 질문 → 수정 전후 비교 → 적용 → 재평가.
 * 사용자가 적용하기 전에는 문서를 건드리지 않고, 적용할 때도 대상 섹션 하나만 바꾼다.
 * 보완안을 만들 때 본 문서 버전과 적용 시점의 버전이 다르면 적용을 거절해 그사이의 수정을 덮어쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadinessFixService {

	private static final int TOP_K = 8;
	private static final double SIMILARITY_THRESHOLD = 0.3;
	private static final int MAX_QUESTIONS = 3;
	private static final String FALLBACK_QUESTION = "이 부분을 문서에 어떻게 적으면 좋을지 알려주세요.";

	private final ReadinessService readinessService;
	private final ReadinessFixRepository fixRepository;
	private final ReadinessEvaluationRepository evaluationRepository;
	private final HandoverDraftRepository handoverDraftRepository;
	private final SourceDocumentRepository sourceDocumentRepository;
	private final VectorStore vectorStore;
	private final ChatClient chatClient;
	private final TransactionTemplate transactionTemplate;

	@Value("${app.ai.analysis-model:gpt-5.4}")
	private String analysisModel;

	/** 자료 검색으로 찾은 발췌 한 건. number는 프롬프트에서 AI가 근거로 가리키는 번호. */
	record Excerpt(int number, UUID sourceId, String fileName, String locator, String text) {
	}

	/** 보완안 생성에 필요한 값(트랜잭션 안에서 모아 둔다). */
	private record FixContext(ReadinessFix fix, ReadinessItem item, HandoverDraftContent content) {
	}

	/** 현재 평가의 부족 항목 하나로 보완안을 만든다. */
	public ReadinessFixResponse create(UUID handoverId, ReadinessArea area) {
		FixContext context = transactionTemplate.execute(status -> {
			ReadinessEvaluation evaluation = readinessService.requireCurrent(handoverId);
			ReadinessItem item = evaluation.item(area)
					.orElseThrow(() -> new BusinessException(ErrorCode.READINESS_STALE));
			if (item.status() == ReadinessStatus.SUFFICIENT) {
				throw new BusinessException(ErrorCode.READINESS_ITEM_SUFFICIENT);
			}
			HandoverDraft draft = loadDraft(handoverId);
			ReadinessFix fix = ReadinessFix.open(handoverId, evaluation.getId(), area,
					area.resolveSection(item.section()), draft.getRevision(), draft.getContent());
			return new FixContext(fix, item, draft.getContent());
		});

		ReadinessFix fix = context.fix();
		applyGeneration(fix, context.content(), generate(fix, context.item(), context.content()));
		ReadinessFix saved = transactionTemplate.execute(status -> fixRepository.save(fix));
		return ReadinessFixResponse.of(saved, fix.getBaseRevision());
	}

	public ReadinessFixResponse get(UUID handoverId, UUID fixId) {
		return transactionTemplate.execute(status -> {
			ReadinessFix fix = loadFix(handoverId, fixId);
			return ReadinessFixResponse.of(fix, currentRevision(handoverId));
		});
	}

	/** 추가 질문에 답하고, 답변까지 반영해 수정안을 다시 만든다. */
	public ReadinessFixResponse answer(UUID handoverId, UUID fixId, FixAnswerRequest request) {
		FixContext context = transactionTemplate.execute(status -> {
			ReadinessFix fix = loadFix(handoverId, fixId);
			HandoverDraft draft = loadDraft(handoverId);
			requireNotStale(fix, draft.getRevision());
			request.answers().forEach(answer -> fix.answer(answer.questionId(), answer.answer().strip()));
			ReadinessItem item = evaluationRepository.findById(fix.getEvaluationId())
					.flatMap(evaluation -> evaluation.item(fix.getArea()))
					.orElseThrow(() -> new BusinessException(ErrorCode.READINESS_STALE));
			return new FixContext(fix, item, draft.getContent());
		});

		ReadinessFix fix = context.fix();
		GeneratedResult generated = generate(fix, context.item(), context.content());

		return transactionTemplate.execute(status -> {
			ReadinessFix current = loadFix(handoverId, fixId);
			long revision = currentRevision(handoverId);
			requireNotStale(current, revision);
			fix.getQuestions().stream()
					.filter(FixQuestion::hasAnswer)
					.forEach(question -> current.answer(question.id(), question.answer()));
			applyGeneration(current, context.content(), generated);
			return ReadinessFixResponse.of(current, revision);
		});
	}

	/**
	 * 사용자가 확인한 수정안을 문서에 적용한다. 대상 섹션만 바꾸고, 적용 뒤 최신 문서로 다시 평가한다.
	 * 재평가가 실패해도 적용은 되돌리지 않는다(응답의 readiness만 null).
	 */
	public ApplyFixResponse apply(UUID handoverId, UUID fixId, long baseRevision) {
		record Applied(ReadinessFixResponse fix, HandoverDraftResponse document) {
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

			draft.replaceContent(fix.getSection().merge(draft.getContent(), fix.getAfter()));
			fix.markApplied(draft.getRevision());
			handoverDraftRepository.flush();
			return new Applied(ReadinessFixResponse.of(fix, draft.getRevision()), HandoverDraftResponse.from(draft));
		});

		ReadinessResponse readiness = null;
		try {
			readiness = readinessService.evaluate(handoverId);
		} catch (RuntimeException e) {
			log.warn("[*] Re-evaluation after applying readiness fix failed: handoverId={}, fixId={}", handoverId, fixId, e);
		}
		return new ApplyFixResponse(applied.fix(), applied.document(), readiness);
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
	 * AI 결과를 보완안 상태로 옮긴다. 대상 섹션이 실제로 바뀐 수정안이 있을 때만 PROPOSED,
	 * 아니면 아직 묻지 않은 질문을 붙여 NEEDS_INPUT으로 둔다.
	 */
	void applyGeneration(ReadinessFix fix, HandoverDraftContent current, GeneratedResult result) {
		DraftSection section = fix.getSection();
		GeneratedFix generated = result.generated();
		boolean changed = generated != null
				&& generated.resolvable()
				&& generated.patch() != null
				&& !section.isEmpty(generated.patch())
				&& !Objects.equals(section.valueOf(generated.patch()), section.valueOf(current));
		if (changed) {
			fix.propose(generated.patch(), blankToNull(generated.changeSummary()),
					citedEvidence(generated.excerptNumbers(), result.excerpts()));
			return;
		}

		Set<String> asked = fix.getQuestions().stream()
				.map(question -> question.question().strip())
				.collect(Collectors.toCollection(HashSet::new));
		List<FixQuestion> newQuestions = new ArrayList<>();
		List<GeneratedFixQuestion> generatedQuestions = generated == null || generated.questions() == null
				? List.of() : generated.questions();
		for (GeneratedFixQuestion question : generatedQuestions) {
			String text = question == null ? null : blankToNull(question.question());
			if (text != null && asked.add(text) && newQuestions.size() < MAX_QUESTIONS) {
				newQuestions.add(new FixQuestion(null, text, blankToNull(question.reason()), null));
			}
		}
		boolean hasUnanswered = fix.getQuestions().stream().anyMatch(question -> !question.hasAnswer());
		if (newQuestions.isEmpty() && !hasUnanswered) {
			newQuestions.add(new FixQuestion(null, asked.contains(FALLBACK_QUESTION)
					? fix.getArea().getLabel() + "에 꼭 들어가야 할 내용을 직접 적어주세요."
					: FALLBACK_QUESTION, null, null));
		}
		fix.askMore(newQuestions);
	}

	/** AI 출력과 그때 보여준 발췌를 함께 들고 다닌다(발췌 번호 → 근거 파일 변환용). */
	record GeneratedResult(GeneratedFix generated, List<Excerpt> excerpts) {
	}

	private GeneratedResult generate(ReadinessFix fix, ReadinessItem item, HandoverDraftContent content) {
		ReadinessArea area = fix.getArea();
		DraftSection section = fix.getSection();
		String criteria = ReadinessRubrics.CURRENT.criteria().get(area);
		List<Excerpt> excerpts = search(fix.getHandoverId(),
				String.join(" ", area.getLabel(), criteria, nullToEmpty(item.summary()), nullToEmpty(item.resolution())));

		SystemPromptTemplate template = new SystemPromptTemplate(ReadinessPrompts.FIX_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.ofEntries(
				Map.entry("area", area.getLabel()),
				Map.entry("criteria", criteria),
				Map.entry("status", item.status().getLabel()),
				Map.entry("summary", nullToEmpty(item.summary())),
				Map.entry("resolution", nullToEmpty(item.resolution())),
				Map.entry("sectionLabel", section.getLabel()),
				Map.entry("sectionField", section.getFieldName()),
				Map.entry("currentValue", readinessService.writeJson(section.valueOf(content))),
				Map.entry("draft", readinessService.writeJson(content)),
				Map.entry("excerpts", describeExcerpts(excerpts)),
				Map.entry("answers", describeAnswers(fix.answeredQuestions()))));
		try {
			GeneratedFix generated = chatClient.prompt()
					.options(OpenAiChatOptions.builder().model(analysisModel))
					.messages(List.of(systemMessage))
					.call()
					.entity(GeneratedFix.class);
			return new GeneratedResult(generated, excerpts);
		} catch (RuntimeException e) {
			log.error("[*] Readiness fix generation failed: handoverId={}, area={}", fix.getHandoverId(), area, e);
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 보완안 생성 중 오류가 발생했습니다.");
		}
	}

	/** 이 인수인계의 청크만 검색한다. 파일명은 트랜잭션 안에서 읽는다(원문 @Lob이 함께 로딩되기 때문). */
	private List<Excerpt> search(UUID handoverId, String query) {
		SearchRequest request = SearchRequest.builder()
				.query(query)
				.topK(TOP_K)
				.similarityThreshold(SIMILARITY_THRESHOLD)
				.filterExpression(new FilterExpressionBuilder().eq("handoverId", handoverId.toString()).build())
				.build();
		List<Document> matches = vectorStore.similaritySearch(request);
		if (matches == null || matches.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> fileNames = transactionTemplate.execute(status ->
				sourceDocumentRepository.findAllByHandoverId(handoverId).stream()
						.collect(Collectors.toMap(SourceDocument::getId, SourceDocument::getFileName)));

		List<Excerpt> excerpts = new ArrayList<>();
		for (Document match : matches) {
			Object rawId = match.getMetadata().get("sourceDocumentId");
			UUID sourceId = rawId == null ? null : UUID.fromString(rawId.toString());
			String fileName = sourceId == null ? null : fileNames.get(sourceId);
			if (fileName == null) {
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

	private static String describeAnswers(List<FixQuestion> answered) {
		if (answered.isEmpty()) {
			return "(답변 없음)";
		}
		return answered.stream()
				.map(question -> "Q: " + question.question() + "\nA: " + question.answer())
				.collect(Collectors.joining("\n\n"));
	}

	private List<ReadinessEvidence> citedEvidence(List<Integer> numbers, List<Excerpt> excerpts) {
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

	private static void requireNotStale(ReadinessFix fix, long currentRevision) {
		if (fix.getStatus().isOpen() && fix.isStaleAgainst(currentRevision)) {
			throw new BusinessException(ErrorCode.AI_DRAFT_REVISION_CONFLICT,
					"보완안을 만든 뒤 문서가 바뀌었습니다. 최신 문서로 보완안을 다시 만들어주세요.");
		}
	}

	private static String nullToEmpty(String text) {
		return text == null ? "" : text;
	}

	private static String blankToNull(String text) {
		return (text == null || text.isBlank()) ? null : text.strip();
	}
}
