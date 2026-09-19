package com.baton.readiness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
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
import com.baton.ai.SourceDocumentStatus;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.readiness.ReadinessItemNormalizer.SourceRef;
import com.baton.readiness.dto.DeferredQuestionResponse;
import com.baton.readiness.dto.GeneratedAssessment;
import com.baton.readiness.dto.ReadinessResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인수인계 문서 준비도 평가. AI는 영역별 상태(충분·일부 부족·누락·충돌)만 정하고, 점수는 평가 기준 버전의 배점으로 서버가 계산한다.
 * 문서·업로드 자료·평가 기준 버전이 같으면 이전 결과를 재사용해 같은 내용에는 항상 같은 점수가 나온다.
 * 외부 AI 호출은 트랜잭션 밖에서 하고, 읽기·저장만 짧게 트랜잭션으로 묶는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadinessService {

	/** 평가 프롬프트에 넣을 업로드 자료 원문 상한(문자). 넘으면 뒤를 자른다. */
	static final int MAX_DOCUMENTS_CHARS = 100_000;

	private final HandoverDraftRepository handoverDraftRepository;
	private final SourceDocumentRepository sourceDocumentRepository;
	private final ReadinessEvaluationRepository evaluationRepository;
	private final ReadinessItemNormalizer normalizer;
	private final ChatClient chatClient;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;
	private final ClarificationQuestionRepository clarificationQuestionRepository;

	@Value("${app.ai.analysis-model:gpt-5.4}")
	private String analysisModel;

	/**
	 * 평가 직전 상태. hash가 같으면 같은 평가 결과를 쓴다.
	 * 업로드 원문(@Lob)은 트랜잭션 안에서 문자열로 뽑아 둔다.
	 */
	record Snapshot(
			UUID handoverId,
			HandoverDraftContent content,
			long revision,
			List<SourceRef> sources,
			String sourcesDescription,
			String documentsText,
			String hash) {
	}

	/**
	 * 현재 문서 기준 평가 결과. 현재 내용과 같은 평가가 있으면 그것을, 없으면 가장 최근 평가를 stale=true로 준다.
	 * AI를 호출하지 않는다.
	 */
	public ReadinessResponse getLatest(UUID handoverId) {
		return transactionTemplate.execute(status -> {
			Optional<ReadinessEvaluation> current = findCurrent(handoverId, currentHash(handoverId));
			if (current.isPresent()) {
				return toResponse(current.get(), false);
			}
			ReadinessEvaluation latest = evaluationRepository.findFirstByHandoverIdOrderByCreatedAtDesc(handoverId)
					.orElseThrow(() -> new BusinessException(ErrorCode.READINESS_NOT_EVALUATED));
			return toResponse(latest, true);
		});
	}

	/** 현재 문서를 평가한다. 같은 내용의 평가가 이미 있으면 AI를 다시 부르지 않는다. */
	public ReadinessResponse evaluate(UUID handoverId) {
		return toResponseInTransaction(evaluateInternal(handoverId), false);
	}

	ReadinessEvaluation evaluateInternal(UUID handoverId) {
		Snapshot snapshot = transactionTemplate.execute(status -> snapshot(handoverId));
		Optional<ReadinessEvaluation> cached = transactionTemplate.execute(
				status -> findCurrent(handoverId, snapshot.hash()));
		if (cached.isPresent()) {
			return cached.get();
		}

		ReadinessRubric rubric = ReadinessRubrics.CURRENT;
		GeneratedAssessment generated = generateAssessment(snapshot, rubric, EnumSet.allOf(ReadinessArea.class));
		List<ReadinessItem> items = normalizer.normalize(generated, snapshot.content(), snapshot.sources());

		return transactionTemplate.execute(status -> evaluationRepository.save(ReadinessEvaluation.create(
				handoverId, rubric, snapshot.hash(), snapshot.revision(), items)));
	}

	/**
	 * 보완안 적용 뒤 재평가. 바뀐 섹션이 걸린 영역(areas)만 AI로 다시 평가하고, 나머지 영역은 직전 평가(base) 결과를 그대로 쓴다
	 * — 고치지 않은 영역의 상태가 흔들려 오른 점수를 상쇄하지 않게. 직전 평가가 없거나 평가 기준 버전이 다르면 전체를 평가한다.
	 */
	public ReadinessResponse reevaluate(UUID handoverId, UUID baseEvaluationId, Set<ReadinessArea> areas) {
		Snapshot snapshot = transactionTemplate.execute(status -> snapshot(handoverId));
		Optional<ReadinessEvaluation> cached = transactionTemplate.execute(
				status -> findCurrent(handoverId, snapshot.hash()));
		if (cached.isPresent()) {
			return toResponseInTransaction(cached.get(), false);
		}
		ReadinessRubric rubric = ReadinessRubrics.CURRENT;
		Optional<ReadinessEvaluation> base = transactionTemplate.execute(status -> evaluationRepository.findById(baseEvaluationId))
				.filter(evaluation -> evaluation.getRubricVersion().equals(rubric.version()));
		if (base.isEmpty() || areas.isEmpty()) {
			return evaluate(handoverId);
		}

		GeneratedAssessment generated = generateAssessment(snapshot, rubric, areas);
		List<ReadinessItem> fresh = normalizer.normalize(generated, snapshot.content(), snapshot.sources());
		List<ReadinessItem> items = mergeItems(base.get(), fresh, areas);

		ReadinessEvaluation saved = transactionTemplate.execute(status -> evaluationRepository.save(ReadinessEvaluation.create(
				handoverId, rubric, snapshot.hash(), snapshot.revision(), items)));
		return toResponseInTransaction(saved, false);
	}

	/** 다시 평가한 영역은 새 결과로, 나머지는 직전 평가 결과로. 영역 정의 순서를 유지한다. */
	static List<ReadinessItem> mergeItems(ReadinessEvaluation base, List<ReadinessItem> fresh, Set<ReadinessArea> areas) {
		return List.of(ReadinessArea.values()).stream()
				.map(area -> (areas.contains(area) ? fresh.stream() : base.getItems().stream())
						.filter(item -> item.area() == area)
						.findFirst())
				.flatMap(Optional::stream)
				.toList();
	}

	/**
	 * 현재 문서 내용과 일치하는 평가(보완 대상 선택용). 트랜잭션 안에서 호출한다.
	 * 평가가 아예 없으면 404, 있지만 문서가 바뀌었으면 409.
	 */
	ReadinessEvaluation requireCurrent(UUID handoverId) {
		return findCurrent(handoverId, currentHash(handoverId)).orElseThrow(() -> {
			boolean evaluatedBefore = evaluationRepository.findFirstByHandoverIdOrderByCreatedAtDesc(handoverId)
					.isPresent();
			return new BusinessException(evaluatedBefore ? ErrorCode.READINESS_STALE : ErrorCode.READINESS_NOT_EVALUATED);
		});
	}

	ReadinessResponse toResponse(ReadinessEvaluation evaluation, boolean stale) {
		ReadinessRubric rubric = ReadinessRubrics.find(evaluation.getRubricVersion())
				.orElseThrow(() -> new IllegalStateException("알 수 없는 평가 기준 버전: " + evaluation.getRubricVersion()));
		return ReadinessResponse.of(evaluation, rubric, stale, deferredQuestions(evaluation.getHandoverId()));
	}

	/**
	 * 확인 질문은 evidence·answer가 PostgreSQL Large Object(@Lob)인 엔티티다.
	 * open-in-view가 꺼진 상태에서 응답을 만들면 Hibernate가 그 스트림을 자동 커밋 연결로 읽을 수 있으므로,
	 * 질문 조회부터 DTO 변환까지는 반드시 짧은 트랜잭션 안에서 끝낸다. AI 호출은 이 경계 밖에 있다.
	 */
	private ReadinessResponse toResponseInTransaction(ReadinessEvaluation evaluation, boolean stale) {
		return transactionTemplate.execute(status -> toResponse(evaluation, stale));
	}

	/** "나중에 답하기"로 미룬 확인 질문을 중요도순으로. 평가 결과와 달리 매번 현재 상태로 읽는다(답하면 바로 빠진다). */
	private List<DeferredQuestionResponse> deferredQuestions(UUID handoverId) {
		return deferredClarificationQuestions(handoverId).stream()
				.map(q -> DeferredQuestionResponse.from(q, areaOf(q)))
				.toList();
	}

	/** 미룬 확인 질문(중요도순). 보완을 시작할 때 해당 영역의 질문으로 함께 묻는다. 트랜잭션 안에서 호출한다. */
	List<ClarificationQuestion> deferredClarificationQuestions(UUID handoverId) {
		return clarificationQuestionRepository
				.findAllByHandoverIdAndStatus(handoverId, ClarificationQuestionStatus.DEFERRED).stream()
				.sorted(Comparator.comparing((ClarificationQuestion q) -> q.getPriority() == null ? Integer.MAX_VALUE : q.getPriority())
						.thenComparing(ClarificationQuestion::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}

	/** 첫 번째 반영 위치가 속한 영역. 반영 위치가 없던 옛 질문은 예외 대응(확인 질문의 기본 반영 위치)으로 본다. */
	static ReadinessArea areaOf(ClarificationQuestion question) {
		List<DraftSection> sections = question.getTargetSections();
		return ReadinessArea.of(sections.isEmpty() ? DraftSection.RULES_AND_EXCEPTIONS : sections.get(0));
	}

	private Optional<ReadinessEvaluation> findCurrent(UUID handoverId, String hash) {
		return evaluationRepository.findFirstByHandoverIdAndContentHashOrderByCreatedAtDesc(handoverId, hash);
	}

	private String currentHash(UUID handoverId) {
		return contentHash(ReadinessRubrics.CURRENT.version(), loadDraft(handoverId).getContent(),
				indexedSources(handoverId));
	}

	private Snapshot snapshot(UUID handoverId) {
		HandoverDraft draft = loadDraft(handoverId);
		List<SourceDocument> sources = indexedSources(handoverId);
		String hash = contentHash(ReadinessRubrics.CURRENT.version(), draft.getContent(), sources);
		List<SourceRef> sourceRefs = sources.stream()
				.map(source -> new SourceRef(source.getId(), source.getFileName(), source.getExtractedText(), source.getPdfTextLocations()))
				.toList();
		return new Snapshot(handoverId, draft.getContent(), draft.getRevision(), sourceRefs,
				describeSources(sources), documentsText(sources), hash);
	}

	private HandoverDraft loadDraft(UUID handoverId) {
		return handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
	}

	private List<SourceDocument> indexedSources(UUID handoverId) {
		return sourceDocumentRepository.findAllByHandoverId(handoverId).stream()
				.filter(source -> source.getStatus() == SourceDocumentStatus.INDEXED && source.isEnabled())
				.sorted(Comparator.comparing(SourceDocument::getId))
				.toList();
	}

	/** 평가 기준 버전 + 문서 내용 + 업로드 자료(id·수정 시각)의 SHA-256. 문서 버전 번호는 넣지 않는다(내용이 같으면 같은 해시). */
	String contentHash(String rubricVersion, HandoverDraftContent content, List<SourceDocument> sources) {
		StringBuilder input = new StringBuilder()
				.append(rubricVersion).append('\n')
				.append(writeJson(content)).append('\n');
		sources.forEach(source -> input.append(source.getId()).append('@').append(source.getUpdatedAt()).append('\n'));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private GeneratedAssessment generateAssessment(Snapshot snapshot, ReadinessRubric rubric, Set<ReadinessArea> areas) {
		SystemPromptTemplate template = new SystemPromptTemplate(ReadinessPrompts.EVALUATE_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of(
				"criteria", describeCriteria(rubric, areas),
				"draft", writeJson(snapshot.content()),
				"sources", snapshot.sourcesDescription(),
				"documents", snapshot.documentsText()));
		try {
			return chatClient.prompt()
					.options(OpenAiChatOptions.builder().model(analysisModel))
					.messages(List.of(systemMessage))
					.call()
					.entity(GeneratedAssessment.class);
		} catch (RuntimeException e) {
			log.error("[*] Readiness evaluation failed for handoverId={}", snapshot.handoverId(), e);
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 준비도 평가 중 오류가 발생했습니다.");
		}
	}

	/** 평가할 영역만. 섹션은 "화면 이름(코드)"로 넘겨 AI가 문장에는 화면 이름을, targetSections에는 코드를 쓰게 한다. */
	static String describeCriteria(ReadinessRubric rubric, Set<ReadinessArea> areas) {
		return List.of(ReadinessArea.values()).stream()
				.filter(areas::contains)
				.map(area -> "- %s / %s / %s / %s".formatted(area.name(), area.getLabel(), rubric.criteria().get(area),
						area.getSections().stream()
								.map(section -> section.getLabel() + "(" + section.name() + ")")
								.collect(Collectors.joining(", "))))
				.collect(Collectors.joining("\n"));
	}

	private static String describeSources(List<SourceDocument> sources) {
		if (sources.isEmpty()) {
			return "(업로드 자료 없음)";
		}
		return sources.stream()
				.map(source -> "- " + source.getFileName() + " / " + source.getUpdatedAt())
				.collect(Collectors.joining("\n"));
	}

	static String documentsText(List<SourceDocument> sources) {
		String text = sources.stream()
				.map(source -> "### " + source.getFileName() + "\n" + source.getExtractedText())
				.collect(Collectors.joining("\n\n"));
		if (text.isEmpty()) {
			return "(업로드 자료 없음)";
		}
		return text.length() > MAX_DOCUMENTS_CHARS
				? text.substring(0, MAX_DOCUMENTS_CHARS) + "\n...(이하 생략)"
				: text;
	}

	String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("JSON 직렬화 실패", e);
		}
	}
}
