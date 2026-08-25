package com.baton.ai;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.baton.ai.dto.AccessItem;
import com.baton.ai.dto.AnalysisResult;
import com.baton.ai.dto.ClarificationQuestionResponse;
import com.baton.ai.dto.ConfirmedCriterion;
import com.baton.ai.dto.HandoverBriefingResponse;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.ai.dto.HandoverDraftResponse;
import com.baton.ai.dto.QuestionAnswerRequest;
import com.baton.ai.dto.ScheduleItem;
import com.baton.ai.dto.Stakeholder;
import com.baton.ai.dto.TaskItem;
import com.baton.ai.dto.ToolItem;
import com.baton.auth.User;
import com.baton.auth.UserRepository;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverRepository;
import com.baton.handover.HandoverStatus;
import com.baton.handover.ParticipantRole;
import com.baton.handover.WorkScope;
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
	private final UserRepository userRepository;
	private final TransactionTemplate transactionTemplate;
	private final HandoverRepository handoverRepository;

	/**
	 * 인수인계 자료 생성 전용 모델(초안·질문·재생성). 품질이 중요한 이 경로만 좋은 모델을 쓰고,
	 * 채팅 Q&A·브리핑은 전역 chat 모델(application.yml, 더 빠르고 저렴)을 그대로 쓴다.
	 */
	@Value("${app.ai.analysis-model:gpt-5.4}")
	private String analysisModel;

	private static final DateTimeFormatter UPDATED_FMT =
			DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm", Locale.KOREA).withZone(ZoneId.of("Asia/Seoul"));

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
			List<ClarificationQuestion> questions = new ArrayList<>(generatedQuestions.stream()
					.map(q -> ClarificationQuestion.create(
							handoverId, q.type(), q.questionText(), q.reason(), q.evidence(), q.options()))
					.toList());
			if (questions.isEmpty()) {
				// 안전장치: 모델이 질문을 하나도 안 냈을 때도 항상 최소 1개는 인계자에게 확인받는다(프롬프트만으론 100% 보장 불가).
				questions.add(ClarificationQuestion.create(handoverId, ClarificationQuestionType.INTERVIEW,
						"자료에 담기지 않았지만 후임자가 꼭 알아야 할 내용이 있나요? 있다면 알려주세요.",
						"자료만으로는 놓칠 수 있는 맥락을 인계자에게 직접 확인하기 위한 기본 질문입니다.",
						null, List.of()));
			}
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

	/** 초안이 아직 없으면 예외 대신 null. 검토 상세처럼 초안 유무와 무관하게 화면을 구성할 때 쓴다. */
	@Transactional(readOnly = true)
	public HandoverDraftResponse findDraftOrNull(UUID handoverId) {
		return handoverDraftRepository.findByHandoverId(handoverId)
				.map(HandoverDraftResponse::from)
				.orElse(null);
	}

	/**
	 * 인수자용 첫날 요약. 초안 전체가 아니라 당장 필요한 필드만 추려서 반환하고,
	 * AI가 쓴 환영 브리핑 문장을 곁들인다. 브리핑 문장은 초안이 바뀌기 전까지 재사용한다.
	 */
	@Transactional
	public HandoverBriefingResponse getBriefing(UUID handoverId) {
		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));

		if (draft.getBriefingSummary() == null) {
			draft.cacheBriefingSummary(generateBriefingSummary(draft.getContent()));
		}

		return HandoverBriefingResponse.from(draft);
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
				.options(OpenAiChatOptions.builder().model(analysisModel))
				.messages(List.of(systemMessage))
				.call()
				.entity(AnalysisResult.class);
	}

	private String generateBriefingSummary(HandoverDraftContent content) {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.BRIEFING_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of("draft", writeJson(content)));

		return chatClient.prompt()
				.messages(List.of(systemMessage))
				.call()
				.content();
	}

	private HandoverDraftContent regenerateDraft(HandoverDraftContent currentDraft, String qnaText) {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.REGENERATE_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of(
				"draft", writeJson(currentDraft),
				"qna", qnaText));

		return chatClient.prompt()
				.options(OpenAiChatOptions.builder().model(analysisModel))
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

	/**
	 * 초안을 고정 섹션 Markdown 문서로 변환한다.
	 * 섹션 순서·제목(# / ##)은 항상 동일하며, 데이터가 없는 섹션은 "- (없음)"으로 채운다.
	 */
	public String exportMarkdown(Handover handover) {
		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handover.getId())
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
		HandoverDraftContent content = draft.getContent();

		StringBuilder md = new StringBuilder();
		md.append("# 업무 인수인계\n\n");
		appendMeta(md, handover, draft.getUpdatedAt());

		md.append("## 업무 개요\n");
		appendParagraphAsBullets(md, content.purpose());

		md.append("\n## 진행 중인 업무\n");
		appendTasks(md, content.ongoingTasks());

		md.append("\n## 반복 업무\n");
		appendTasks(md, content.recurringTasks());

		md.append("\n## 업무 기준과 예외\n");
		appendBullets(md, content.rulesAndExceptions());

		md.append("\n## 주요 관계자\n");
		if (content.stakeholders() == null || content.stakeholders().isEmpty()) {
			md.append("- (없음)\n");
		} else {
			content.stakeholders().forEach(s -> md.append("- ").append(nullToDash(s.name())).append(" / ")
					.append(nullToDash(s.team())).append(" / ").append(nullToDash(s.helpWith())).append("\n"));
		}

		md.append("\n## 사용 도구와 자료\n");
		if (content.tools() == null || content.tools().isEmpty()) {
			md.append("- (없음)\n");
		} else {
			content.tools().forEach(t -> md.append("- ").append(nullToDash(t.name()))
					.append(" — ").append(nullToDash(t.description())).append("\n"));
		}

		md.append("\n## 업무 일정\n");
		if (content.schedule() == null || content.schedule().isEmpty()) {
			md.append("- (없음)\n");
		} else {
			content.schedule().forEach(s -> md.append("- ").append(nullToDash(s.cycle())).append(" / ")
					.append(nullToDash(s.task())).append(" / ").append(nullToDash(s.detail())).append("\n"));
		}

		md.append("\n## 접근 권한과 계정\n");
		if (content.accessAccounts() == null || content.accessAccounts().isEmpty()) {
			md.append("- (없음)\n");
		} else {
			content.accessAccounts().forEach(a -> md.append("- ").append(nullToDash(a.tool())).append(" / ")
					.append(nullToDash(a.permission())).append(" / ").append(nullToDash(a.status())).append("\n"));
		}

		md.append("\n## 첫 주 체크리스트\n");
		if (content.firstWeekChecklist() == null || content.firstWeekChecklist().isEmpty()) {
			md.append("- (없음)\n");
		} else {
			content.firstWeekChecklist().forEach(i -> md.append("- [ ] ").append(i).append("\n"));
		}

		md.append("\n## 확인된 업무 기준\n");
		if (content.confirmedCriteria() == null || content.confirmedCriteria().isEmpty()) {
			md.append("- (없음)\n");
		} else {
			content.confirmedCriteria().forEach(c -> md.append("- ").append(nullToDash(c.label()))
					.append(": ").append(nullToDash(c.value())).append("\n"));
		}

		return md.toString();
	}

	/** 문서 상단 메타(인계자/인수자/담당업무/자료수/상태/업데이트). */
	private void appendMeta(StringBuilder md, Handover handover, Instant updatedAt) {
		String owner = userName(handover.getOwnerId());
		String recipients = handover.getParticipants().stream()
				.filter(p -> p.getRole() == ParticipantRole.RECIPIENT)
				.map(p -> userName(p.getUserId()))
				.collect(Collectors.joining(", "));
		if (recipients.isBlank()) {
			recipients = "-";
		}
		String scopes = handover.getWorkScopes().stream()
				.map(WorkScope::getTitle)
				.collect(Collectors.joining(" · "));
		if (scopes.isBlank()) {
			scopes = "-";
		}
		long fileCount = sourceDocumentRepository.findAllByHandoverId(handover.getId()).size();

		md.append("> ").append(owner).append("님의 업무를 ").append(recipients).append("님에게 전달합니다.\n");
		md.append("- 인계자: ").append(owner).append("\n");
		md.append("- 인수자: ").append(recipients).append("\n");
		md.append("- 담당 업무: ").append(scopes).append("\n");
		md.append("- 참고 자료: 업로드 파일 ").append(fileCount).append("개\n");
		md.append("- 상태: ").append(statusLabel(handover.getStatus())).append(" · 최신 버전\n");
		md.append("- 업데이트: ").append(UPDATED_FMT.format(updatedAt)).append("\n\n");
	}

	private String userName(UUID userId) {
		return userRepository.findById(userId).map(User::getName).orElse("-");
	}

	private String statusLabel(HandoverStatus status) {
		return switch (status) {
			case DRAFT -> "작성 중";
			case ANALYZING -> "분석 중";
			case ANSWERING -> "질문 답변 중";
			case EDITING -> "수정 중";
			case PENDING_REVIEW -> "검토 대기";
			case REVISION_REQUESTED -> "보완 요청";
			case APPROVED -> "승인 완료";
			case COMPLETED -> "인수 완료";
		};
	}

	/** 진행/반복 업무를 ### 소제목 + 항목 목록으로. */
	private void appendTasks(StringBuilder md, List<TaskItem> tasks) {
		if (tasks == null || tasks.isEmpty()) {
			md.append("- (없음)\n");
			return;
		}
		for (TaskItem task : tasks) {
			md.append("\n### ").append(nullToDash(task.title())).append("\n\n");
			md.append("- 설명: ").append(nullToDash(task.description())).append("\n");
			md.append("- 현재 상태: ").append(nullToDash(task.status())).append("\n");
			md.append("- 다음 할 일: ").append(nullToDash(task.nextAction())).append("\n");
			md.append("- 일정·담당: ").append(nullToDash(task.schedule())).append("\n");
		}
	}

	private void appendBullets(StringBuilder md, List<String> items) {
		if (items == null || items.isEmpty()) {
			md.append("- (없음)\n");
			return;
		}
		items.forEach(i -> md.append("- ").append(i).append("\n"));
	}

	/** 한 문단(purpose)을 줄 단위 불릿으로. 기존 불릿 기호는 제거해 중복을 막는다. */
	private void appendParagraphAsBullets(StringBuilder md, String text) {
		if (text == null || text.isBlank()) {
			md.append("- (없음)\n");
			return;
		}
		for (String line : text.split("\\R")) {
			String cleaned = line.strip().replaceFirst("^[-*]\\s*", "");
			if (!cleaned.isBlank()) {
				md.append("- ").append(cleaned).append("\n");
			}
		}
	}

	private String nullToDash(String value) {
		return (value == null || value.isBlank()) ? "-" : value;
	}

	public record AnalysisExecutionResult(HandoverDraftResponse draft, int questionCount) {
	}

	private record CompletionInput(HandoverDraftContent currentDraft, String qnaText, boolean hasAnswers) {
	}
}
