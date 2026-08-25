package com.baton.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.baton.ai.dto.AnalysisResult;
import com.baton.ai.dto.ClarificationQuestionResponse;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.ai.dto.HandoverDraftResponse;
import com.baton.ai.dto.QuestionAnswerRequest;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 업로드된 문서를 근거로 구조화된 인수인계 초안과, 자료만으로 판단 안 되는 부분에 대한
 * 확인 질문을 생성한다. 질문에 답이 달리면 그 내용을 반영해 초안을 다시 만든다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagAnalysisService {

	private final SourceDocumentRepository sourceDocumentRepository;
	private final HandoverDraftRepository handoverDraftRepository;
	private final ClarificationQuestionRepository clarificationQuestionRepository;
	private final ChatClient chatClient;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;
	private final HandoverRepository handoverRepository;

	/** 외부 AI 호출은 DB 트랜잭션 밖에서 수행하고, 입력 조회와 결과 저장만 짧게 트랜잭션으로 묶는다. */
	public AnalysisExecutionResult analyze(UUID handoverId) {
		String combinedText = transactionTemplate.execute(status -> loadCombinedText(handoverId));
		AnalysisResult result = generateAnalysis(combinedText);

		if (result == null || result.draft() == null) {
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI가 유효한 초안을 반환하지 않았습니다.");
		}

		List<com.baton.ai.dto.GeneratedQuestion> generatedQuestions =
				result.questions() == null ? List.of() : result.questions();

		return transactionTemplate.execute(status -> {
			HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
					.orElseGet(() -> HandoverDraft.create(handoverId, result.draft()));
			draft.replaceContent(result.draft());
			handoverDraftRepository.save(draft);

			clarificationQuestionRepository.deleteAllByHandoverId(handoverId);
			List<ClarificationQuestion> questions = generatedQuestions.stream()
					.map(q -> ClarificationQuestion.create(
							handoverId, q.type(), q.questionText(), q.reason(), q.evidence(), q.options()))
					.toList();
			clarificationQuestionRepository.saveAll(questions);

			return new AnalysisExecutionResult(HandoverDraftResponse.from(draft), questions.size());
		});
	}

	@Transactional(readOnly = true)
	public HandoverDraftResponse getDraft(UUID handoverId) {
		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
		return HandoverDraftResponse.from(draft);
	}

	/** 사람이 초안을 직접 수정한다(자동저장). 필드 단위가 아니라 content 전체를 교체한다. */
	@Transactional
	public HandoverDraftResponse updateDraft(UUID handoverId, HandoverDraftContent content) {
		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
		draft.replaceContent(content);
		return HandoverDraftResponse.from(draft);
	}

	@Transactional(readOnly = true)
	public List<ClarificationQuestionResponse> getQuestions(UUID handoverId, ClarificationQuestionType type) {
		List<ClarificationQuestion> questions = type == null
				? clarificationQuestionRepository.findAllByHandoverId(handoverId)
				: clarificationQuestionRepository.findAllByHandoverIdAndType(handoverId, type);
		return questions.stream()
				.map(ClarificationQuestionResponse::from)
				.toList();
	}

	@Transactional
	public ClarificationQuestionResponse answerQuestion(UUID handoverId, UUID questionId, QuestionAnswerRequest request) {
		ClarificationQuestion question = clarificationQuestionRepository.findById(questionId)
				.filter(q -> q.getHandoverId().equals(handoverId))
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_QUESTION_NOT_FOUND));

		if (request.skipped()) {
			question.skip();
		} else {
			question.answer(request.answer());
		}

		return ClarificationQuestionResponse.from(question);
	}

	/** 모든 질문이 답변/건너뛰기 상태인지 확인한 뒤, 답변 내용을 초안에 반영한다. */
	public HandoverDraftResponse completeQuestions(UUID handoverId) {
		CompletionInput input = transactionTemplate.execute(status -> loadCompletionInput(handoverId));

		if (!input.hasAnswers()) {
			return transactionTemplate.execute(status -> finishQuestionsWithoutRegeneration(handoverId));
		}

		HandoverDraftContent updated = regenerateDraft(input.currentDraft(), input.qnaText());
		return transactionTemplate.execute(status -> {
			HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
					.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
			draft.replaceContent(updated);
			loadHandover(handoverId).markQuestionsCompleted();
			return HandoverDraftResponse.from(draft);
		});
	}

	private String loadCombinedText(UUID handoverId) {
		List<SourceDocument> documents = sourceDocumentRepository.findAllByHandoverId(handoverId).stream()
				.filter(document -> document.getStatus() == SourceDocumentStatus.INDEXED)
				.toList();

		if (documents.isEmpty()) {
			throw new BusinessException(ErrorCode.AI_NO_DOCUMENTS);
		}

		return documents.stream()
				.map(document -> "### " + document.getFileName() + "\n" + document.getExtractedText())
				.collect(Collectors.joining("\n\n"));
	}

	private CompletionInput loadCompletionInput(UUID handoverId) {
		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
		List<ClarificationQuestion> questions = clarificationQuestionRepository.findAllByHandoverId(handoverId);

		boolean hasPending = questions.stream()
				.anyMatch(q -> q.getStatus() == ClarificationQuestionStatus.PENDING);
		if (hasPending) {
			throw new BusinessException(ErrorCode.AI_QUESTIONS_INCOMPLETE);
		}

		List<ClarificationQuestion> answered = questions.stream()
				.filter(q -> q.getStatus() == ClarificationQuestionStatus.ANSWERED)
				.toList();
		String qnaText = answered.stream()
				.map(q -> "Q: " + q.getQuestionText() + "\nA: " + q.getAnswer())
				.collect(Collectors.joining("\n\n"));
		return new CompletionInput(draft.getContent(), qnaText, !answered.isEmpty());
	}

	private HandoverDraftResponse finishQuestionsWithoutRegeneration(UUID handoverId) {
		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
		loadHandover(handoverId).markQuestionsCompleted();
		return HandoverDraftResponse.from(draft);
	}

	private Handover loadHandover(UUID handoverId) {
		return handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
	}

	private AnalysisResult generateAnalysis(String documentsText) {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.ANALYSIS_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of("documents", documentsText));

		return chatClient.prompt()
				.messages(List.of(systemMessage))
				.call()
				.entity(AnalysisResult.class);
	}

	private HandoverDraftContent regenerateDraft(HandoverDraftContent currentDraft, String qnaText) {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.REGENERATE_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of(
				"draft", writeJson(currentDraft),
				"qna", qnaText));

		return chatClient.prompt()
				.messages(List.of(systemMessage))
				.call()
				.entity(HandoverDraftContent.class);
	}

	private String writeJson(HandoverDraftContent content) {
		try {
			return objectMapper.writeValueAsString(content);
		} catch (Exception e) {
			log.error("[*] Failed to serialize existing draft", e);
			throw new BusinessException(ErrorCode.INTERNAL_ERROR);
		}
	}

	/** 초안을 Markdown 문서로 변환한다. */
	public String exportMarkdown(UUID handoverId, String title) {
		HandoverDraftContent content = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND))
				.getContent();

		StringBuilder md = new StringBuilder();
		md.append("# ").append(title).append("\n\n");
		md.append("## 업무 목적\n").append(nullToDash(content.purpose())).append("\n\n");
		md.append("## 인수인계 완료 기준\n").append(nullToDash(content.completionCriteria())).append("\n\n");

		md.append("## 진행 중인 업무\n");
		appendTasks(md, content.ongoingTasks());
		md.append("\n## 반복 업무\n");
		appendTasks(md, content.recurringTasks());

		md.append("\n## 업무 기준과 예외\n");
		if (content.rulesAndExceptions() == null || content.rulesAndExceptions().isEmpty()) {
			md.append("- (없음)\n");
		} else {
			content.rulesAndExceptions().forEach(rule -> md.append("- ").append(rule).append("\n"));
		}

		md.append("\n## 주요 관계자\n");
		if (content.stakeholders() == null || content.stakeholders().isEmpty()) {
			md.append("- (없음)\n");
		} else {
			content.stakeholders().forEach(s -> md.append("- **").append(s.name()).append("** (")
					.append(s.team()).append(") — ").append(s.helpWith()).append("\n"));
		}

		return md.toString();
	}

	private void appendTasks(StringBuilder md, List<com.baton.ai.dto.TaskItem> tasks) {
		if (tasks == null || tasks.isEmpty()) {
			md.append("- (없음)\n");
			return;
		}
		tasks.forEach(task -> md.append("- **").append(task.title()).append("** [")
				.append(task.status()).append("] — ").append(task.description())
				.append(" (다음 행동: ").append(task.nextAction()).append(")\n"));
	}

	private String nullToDash(String value) {
		return (value == null || value.isBlank()) ? "-" : value;
	}

	public record AnalysisExecutionResult(HandoverDraftResponse draft, int questionCount) {
	}

	private record CompletionInput(HandoverDraftContent currentDraft, String qnaText, boolean hasAnswers) {
	}
}
