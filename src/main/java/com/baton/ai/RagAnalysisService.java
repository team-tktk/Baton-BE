package com.baton.ai;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.dto.AccessItem;
import com.baton.ai.dto.AnalysisResult;
import com.baton.ai.dto.ClarificationQuestionResponse;
import com.baton.ai.dto.ConfirmedCriterion;
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

	private static final DateTimeFormatter UPDATED_FMT =
			DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm", Locale.KOREA).withZone(ZoneId.of("Asia/Seoul"));

	@Transactional
	public HandoverDraftResponse analyze(UUID handoverId) {
		List<SourceDocument> documents = sourceDocumentRepository.findAllByHandoverId(handoverId).stream()
				.filter(document -> document.getStatus() == SourceDocumentStatus.INDEXED)
				.toList();

		if (documents.isEmpty()) {
			throw new BusinessException(ErrorCode.AI_NO_DOCUMENTS);
		}

		String combinedText = documents.stream()
				.map(document -> "### " + document.getFileName() + "\n" + document.getExtractedText())
				.collect(Collectors.joining("\n\n"));

		AnalysisResult result = generateAnalysis(combinedText);

		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseGet(() -> HandoverDraft.create(handoverId, result.draft()));
		draft.replaceContent(result.draft());
		handoverDraftRepository.save(draft);

		clarificationQuestionRepository.deleteAllByHandoverId(handoverId);
		List<ClarificationQuestion> questions = result.questions().stream()
				.map(q -> ClarificationQuestion.create(handoverId, q.questionText(), q.reason(), q.options()))
				.toList();
		clarificationQuestionRepository.saveAll(questions);

		return HandoverDraftResponse.from(draft);
	}

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

	public List<ClarificationQuestionResponse> getQuestions(UUID handoverId) {
		return clarificationQuestionRepository.findAllByHandoverId(handoverId).stream()
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

	/** 답변된 질문 내용을 반영해 초안을 다시 만든다. 답변된 질문이 없으면 기존 초안을 그대로 반환한다. */
	@Transactional
	public HandoverDraftResponse completeQuestions(UUID handoverId) {
		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));

		List<ClarificationQuestion> answered = clarificationQuestionRepository.findAllByHandoverId(handoverId).stream()
				.filter(q -> q.getStatus() == ClarificationQuestionStatus.ANSWERED)
				.toList();

		if (answered.isEmpty()) {
			return HandoverDraftResponse.from(draft);
		}

		String qnaText = answered.stream()
				.map(q -> "Q: " + q.getQuestionText() + "\nA: " + q.getAnswer())
				.collect(Collectors.joining("\n\n"));

		HandoverDraftContent updated = regenerateDraft(draft.getContent(), qnaText);
		draft.replaceContent(updated);

		return HandoverDraftResponse.from(draft);
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
}
