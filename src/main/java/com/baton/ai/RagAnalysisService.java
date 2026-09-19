package com.baton.ai;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.baton.ai.dto.AccessItem;
import com.baton.ai.dto.ClarificationQuestionResponse;
import com.baton.ai.dto.ConfirmedCriterion;
import com.baton.ai.dto.DraftPageA;
import com.baton.ai.dto.DraftPageB;
import com.baton.ai.dto.GeneratedQuestion;
import com.baton.ai.dto.GeneratedQuestions;
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
 * 확인 질문을 생성한다. 초안은 처음 한 번 전체를 만들고, 이후 질문 처리 결과는 관련 섹션에만 반영한다.
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

	private static final Comparator<ClarificationQuestion> QUESTION_ORDER = Comparator
			.comparing((ClarificationQuestion q) -> q.getPriority() == null ? Integer.MAX_VALUE : q.getPriority())
			.thenComparing(ClarificationQuestion::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));

	private static final DateTimeFormatter UPDATED_FMT =
			DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm", Locale.KOREA).withZone(ZoneId.of("Asia/Seoul"));

	/**
	 * 분석 단계 = "확인 질문"만 빠르게 생성한다(출력이 작아 빠름). 무거운 초안 생성은 답변을 받은 뒤
	 * completeQuestions에서 페이지 병렬로 수행한다. 외부 AI 호출은 트랜잭션 밖, 저장만 짧게 트랜잭션.
	 * 재분석이면 이미 처리한 질문(답변·모름·해당 없음·나중에 답하기)은 남겨 두고 같은 뜻의 질문을 다시 만들지 않는다.
	 */
	public AnalysisExecutionResult analyze(UUID handoverId) {
		AnalysisInput input = transactionTemplate.execute(status -> loadAnalysisInput(handoverId));
		List<GeneratedQuestion> candidates = QuestionSelector.dedupe(
				generateQuestions(input.documentsText(), input.askedQuestions()), input.askedQuestions());
		List<GeneratedQuestion> selected = candidates.stream()
				.limit(QuestionSelector.MAX_QUESTIONS)
				.toList();

		return transactionTemplate.execute(status -> {
			clarificationQuestionRepository.deleteAllByHandoverIdAndStatus(handoverId, ClarificationQuestionStatus.PENDING);
			List<ClarificationQuestion> questions = new ArrayList<>();
			for (int i = 0; i < selected.size(); i++) {
				GeneratedQuestion q = selected.get(i);
				questions.add(ClarificationQuestion.create(handoverId, q.type(), q.questionText(), q.reason(),
						q.evidence(), q.options(), targetSectionsOrDefault(q), i + 1));
			}
			if (questions.isEmpty() && input.askedQuestions().isEmpty()) {
				// 안전장치: 처음 분석인데 남은 질문이 하나도 없어도 최소 1개는 인계자에게 확인받는다.
				questions.add(ClarificationQuestion.create(handoverId, ClarificationQuestionType.INTERVIEW,
						"자료에 담기지 않았지만 후임자가 꼭 알아야 할 내용이 있나요? 있다면 알려주세요.",
						"자료만으로는 놓칠 수 있는 맥락을 인계자에게 직접 확인하기 위한 기본 질문입니다.",
						null, List.of(), List.of(DraftSection.RULES_AND_EXCEPTIONS, DraftSection.FIRST_WEEK_CHECKLIST), 1));
			}
			clarificationQuestionRepository.saveAll(questions);
			// 새 질문이 없어도 초안이 아직 없으면 답변 단계로 보내 완료 처리에서 초안을 만들게 한다.
			boolean hasDraft = handoverDraftRepository.findByHandoverId(handoverId).isPresent();
			return new AnalysisExecutionResult(questions.size(), !questions.isEmpty() || !hasDraft);
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

	/**
	 * 채팅 화면에 보여줄 추천 질문. 초안 내용에 근거해 AI가 만들고, 초안이 바뀌기 전까지 재사용한다.
	 * (이전에는 프론트에 고정 문구가 하드코딩되어 있어 모든 인수인계에서 똑같은 질문만 나왔다.)
	 */
	@Transactional
	public List<String> getSuggestedQuestions(UUID handoverId) {
		HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));

		if (draft.getSuggestedQuestions() == null) {
			draft.cacheSuggestedQuestions(generateSuggestedQuestions(draft.getContent()));
		}

		return draft.getSuggestedQuestions();
	}

	/**
	 * 사람이 초안을 직접 수정한다(자동저장). 필드 단위가 아니라 content 전체를 교체한다.
	 * baseRevision이 있으면 현재 버전과 같을 때만 저장한다.
	 */
	@Transactional
	public HandoverDraftResponse updateDraft(UUID handoverId, HandoverDraftContent content, Long baseRevision) {
		HandoverDraft draft = (baseRevision == null
				? handoverDraftRepository.findByHandoverId(handoverId)
				: handoverDraftRepository.findByHandoverIdForUpdate(handoverId))
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
		if (baseRevision != null && baseRevision != draft.getRevision()) {
			throw new BusinessException(ErrorCode.AI_DRAFT_REVISION_CONFLICT);
		}
		draft.replaceContent(content);
		return HandoverDraftResponse.from(draft);
	}

	/** 중요도순(priority 오름차순, 없으면 뒤로), 같으면 생성순. */
	@Transactional(readOnly = true)
	public List<ClarificationQuestionResponse> getQuestions(UUID handoverId, ClarificationQuestionType type) {
		List<ClarificationQuestion> questions = type == null
				? clarificationQuestionRepository.findAllByHandoverId(handoverId)
				: clarificationQuestionRepository.findAllByHandoverIdAndType(handoverId, type);
		return questions.stream()
				.sorted(QUESTION_ORDER)
				.map(ClarificationQuestionResponse::from)
				.toList();
	}

	@Transactional
	public ClarificationQuestionResponse answerQuestion(UUID handoverId, UUID questionId, QuestionAnswerRequest request) {
		ClarificationQuestion question = clarificationQuestionRepository.findById(questionId)
				.filter(q -> q.getHandoverId().equals(handoverId))
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_QUESTION_NOT_FOUND));

		if (request.status() == ClarificationQuestionStatus.ANSWERED) {
			question.answer(request.answer());
		} else {
			question.resolveWithoutAnswer(request.status());
		}

		return ClarificationQuestionResponse.from(question);
	}

	/**
	 * 미응답(PENDING) 질문이 없는지 확인한 뒤 처리 결과를 문서에 반영한다.
	 * - 초안이 없으면(첫 완료) 자료 + 처리 결과로 초안 전체를 "페이지 병렬"로 생성한다.
	 * - 초안이 이미 있으면(재분석 후 완료) 아직 반영되지 않은 질문의 대상 섹션만 갱신한다.
	 * 나중에 답하기(DEFERRED)는 완료를 막지 않고, 답이 달린 뒤 applyAnswers로 반영한다.
	 * 확인 질문 단계(ANSWERING)에서만 가능하다. beforeAiCall은 검증을 모두 통과한 뒤 AI 호출 직전에 부른다(사용량 차감).
	 */
	public HandoverDraftResponse completeQuestions(UUID handoverId, Runnable beforeAiCall) {
		ApplyInput input = transactionTemplate.execute(status -> {
			Handover handover = loadHandover(handoverId);
			if (handover.getStatus() != HandoverStatus.ANSWERING) {
				throw new BusinessException(ErrorCode.HANDOVER_INVALID_STATE,
						"확인 질문에 답하는 단계에서만 인수인계서를 생성할 수 있습니다: " + handover.getStatus());
			}
			List<ClarificationQuestion> questions = clarificationQuestionRepository.findAllByHandoverId(handoverId);
			if (questions.stream().anyMatch(q -> q.getStatus() == ClarificationQuestionStatus.PENDING)) {
				throw new BusinessException(ErrorCode.AI_QUESTIONS_INCOMPLETE);
			}
			return loadApplyInput(handoverId, questions);
		});

		if (input.currentContent() != null) {
			return applyToSections(handoverId, input, true, beforeAiCall);
		}

		List<QuestionSnapshot> resolved = input.questions().stream()
				.filter(q -> q.status().isResolved())
				.toList();
		beforeAiCall.run();
		HandoverDraftContent content = generateDraftPaged(input.documentsText(), toQnaText(resolved));

		return transactionTemplate.execute(status -> {
			HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
					.orElseGet(() -> HandoverDraft.create(handoverId, content));
			draft.replaceContent(content);
			handoverDraftRepository.save(draft);
			markApplied(resolved);
			loadHandover(handoverId).markQuestionsCompleted();
			return HandoverDraftResponse.from(draft);
		});
	}

	/**
	 * 문서가 만들어진 뒤에 답한 질문(나중에 답하기였던 질문 등)을 문서에 반영한다.
	 * 문서 전체를 다시 만들지 않고, 반영되지 않은 질문들의 대상 섹션만 갱신한다. 반영할 게 없으면 현재 문서를 그대로 반환한다.
	 * beforeAiCall은 실제로 AI를 부를 때만 호출 직전에 부른다(사용량 차감).
	 */
	public HandoverDraftResponse applyAnswers(UUID handoverId, Runnable beforeAiCall) {
		ApplyInput input = transactionTemplate.execute(status -> {
			if (handoverDraftRepository.findByHandoverId(handoverId).isEmpty()) {
				throw new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND);
			}
			return loadApplyInput(handoverId, clarificationQuestionRepository.findAllByHandoverId(handoverId));
		});
		return applyToSections(handoverId, input, false, beforeAiCall);
	}

	private HandoverDraftResponse applyToSections(UUID handoverId, ApplyInput input, boolean completeQuestions,
			Runnable beforeAiCall) {
		List<QuestionSnapshot> toApply = input.questions().stream()
				.filter(QuestionSnapshot::needsApply)
				.toList();
		Set<DraftSection> sections = toApply.stream()
				.flatMap(q -> sectionsToUpdate(q).stream())
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(DraftSection.class)));

		if (!sections.isEmpty()) {
			beforeAiCall.run();
		}
		HandoverDraftContent patch = sections.isEmpty()
				? null
				: generateSectionUpdate(input.documentsText(), DraftSection.extract(input.currentContent(), sections),
						toQnaText(toApply), sections);

		return transactionTemplate.execute(status -> {
			HandoverDraft draft = handoverDraftRepository.findByHandoverId(handoverId)
					.orElseThrow(() -> new BusinessException(ErrorCode.AI_DRAFT_NOT_FOUND));
			if (patch != null) {
				// AI 호출 중 사람이 고친 다른 섹션을 덮어쓰지 않도록, 저장 시점의 최신 content에 대상 섹션만 합친다.
				draft.replaceContent(DraftSection.merge(draft.getContent(), patch, sections));
			}
			markApplied(toApply);
			if (completeQuestions) {
				loadHandover(handoverId).markQuestionsCompleted();
			}
			return HandoverDraftResponse.from(draft);
		});
	}

	/** 반영 대상으로 읽었던 상태·답변이 그대로인 질문만 반영 완료로 표시한다(AI 호출 중 답이 바뀌었으면 다음 반영 때 다시 반영). */
	private void markApplied(List<QuestionSnapshot> applied) {
		Instant now = Instant.now();
		Map<UUID, QuestionSnapshot> byId = applied.stream()
				.collect(Collectors.toMap(QuestionSnapshot::id, q -> q));
		clarificationQuestionRepository.findAllById(byId.keySet()).stream()
				.filter(q -> byId.get(q.getId()).matches(q))
				.forEach(q -> q.markApplied(now));
	}

	/** 모름은 "확인 필요" 항목을 첫 주 체크리스트에 남기므로 대상 섹션에 체크리스트를 더한다. */
	private List<DraftSection> sectionsToUpdate(QuestionSnapshot question) {
		List<DraftSection> sections = new ArrayList<>(question.targetSections());
		if (sections.isEmpty()) {
			sections.addAll(defaultTargetSections(question.type()));
		}
		if (question.status() == ClarificationQuestionStatus.UNKNOWN && !sections.contains(DraftSection.FIRST_WEEK_CHECKLIST)) {
			sections.add(DraftSection.FIRST_WEEK_CHECKLIST);
		}
		return sections;
	}

	private List<DraftSection> targetSectionsOrDefault(GeneratedQuestion question) {
		if (question.targetSections() == null) {
			return defaultTargetSections(question.type());
		}
		List<DraftSection> sections = question.targetSections().stream()
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		return sections.isEmpty() ? defaultTargetSections(question.type()) : sections;
	}

	private List<DraftSection> defaultTargetSections(ClarificationQuestionType type) {
		return type == ClarificationQuestionType.CONFLICT
				? List.of(DraftSection.CONFIRMED_CRITERIA)
				: List.of(DraftSection.RULES_AND_EXCEPTIONS);
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

	private AnalysisInput loadAnalysisInput(UUID handoverId) {
		List<String> askedQuestions = clarificationQuestionRepository.findAllByHandoverId(handoverId).stream()
				.filter(q -> q.getStatus() != ClarificationQuestionStatus.PENDING)
				.map(ClarificationQuestion::getQuestionText)
				.toList();
		return new AnalysisInput(loadCombinedText(handoverId), askedQuestions);
	}

	private ApplyInput loadApplyInput(UUID handoverId, List<ClarificationQuestion> questions) {
		HandoverDraftContent currentContent = handoverDraftRepository.findByHandoverId(handoverId)
				.map(HandoverDraft::getContent)
				.orElse(null);
		List<QuestionSnapshot> snapshots = questions.stream()
				.sorted(QUESTION_ORDER)
				.map(QuestionSnapshot::from)
				.toList();
		return new ApplyInput(loadCombinedText(handoverId), currentContent, snapshots);
	}

	/** 처리 결과를 [답변]·[모름]·[해당 없음]으로 구분해 프롬프트용 텍스트로 만든다(RagPrompts.QNA_RULE과 짝). */
	private String toQnaText(List<QuestionSnapshot> questions) {
		return questions.stream()
				.filter(q -> q.status().isResolved())
				.map(q -> {
					String location = "(반영 위치: " + DraftSection.describe(sectionsToUpdate(q)) + ")";
					return switch (q.status()) {
						case ANSWERED -> "[답변] Q: " + q.questionText() + " " + location + "\nA: " + q.answer();
						case UNKNOWN -> "[모름] Q: " + q.questionText() + " " + location;
						default -> "[해당 없음] Q: " + q.questionText() + " " + location;
					};
				})
				.collect(Collectors.joining("\n\n"));
	}

	private Handover loadHandover(UUID handoverId) {
		return handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
	}

	/** 질문만 생성(초안과 분리). 출력이 작아 빠르게 먼저 보여줄 수 있다. */
	private List<GeneratedQuestion> generateQuestions(String documentsText, List<String> askedQuestions) {
		String asked = askedQuestions.isEmpty()
				? "(없음)"
				: askedQuestions.stream().map(q -> "- " + q).collect(Collectors.joining("\n"));
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.QUESTIONS_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of("documents", documentsText, "askedQuestions", asked));

		GeneratedQuestions result = chatClient.prompt()
				.options(OpenAiChatOptions.builder().model(analysisModel))
				.messages(List.of(systemMessage))
				.call()
				.entity(GeneratedQuestions.class);
		return (result == null || result.questions() == null) ? List.of() : result.questions();
	}

	/**
	 * 초안을 두 페이지(A: 핵심 업무, B: 사람·자원·온보딩)로 나눠 병렬 생성한 뒤 하나로 합친다.
	 * 각 호출의 출력이 절반이라 빨라지고, 두 호출이 겹쳐 돌아 벽시계 = 더 느린 하나에 가깝다.
	 */
	private HandoverDraftContent generateDraftPaged(String documentsText, String qnaText) {
		String qna = (qnaText == null || qnaText.isBlank()) ? "(답변 없음)" : qnaText;
		CompletableFuture<DraftPageA> fa = CompletableFuture.supplyAsync(() -> generatePageA(documentsText, qna));
		CompletableFuture<DraftPageB> fb = CompletableFuture.supplyAsync(() -> generatePageB(documentsText, qna));
		try {
			CompletableFuture.allOf(fa, fb).join();
		} catch (RuntimeException e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			log.error("[*] Draft page generation failed", cause);
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 초안 생성 중 오류가 발생했습니다.");
		}
		DraftPageA a = fa.join();
		DraftPageB b = fb.join();
		return new HandoverDraftContent(
				a.purpose(), a.completionCriteria(), a.ongoingTasks(), a.recurringTasks(), a.rulesAndExceptions(),
				b.stakeholders(), b.tools(), b.schedule(), b.accessAccounts(), b.firstWeekChecklist(), b.confirmedCriteria());
	}

	private DraftPageA generatePageA(String documentsText, String qnaText) {
		return generatePage(documentsText, qnaText,
				DraftSection.describe(List.of(DraftSection.PURPOSE, DraftSection.COMPLETION_CRITERIA,
						DraftSection.ONGOING_TASKS, DraftSection.RECURRING_TASKS, DraftSection.RULES_AND_EXCEPTIONS)),
				DraftPageA.class);
	}

	private DraftPageB generatePageB(String documentsText, String qnaText) {
		return generatePage(documentsText, qnaText,
				DraftSection.describe(List.of(DraftSection.STAKEHOLDERS, DraftSection.TOOLS, DraftSection.SCHEDULE,
						DraftSection.ACCESS_ACCOUNTS, DraftSection.FIRST_WEEK_CHECKLIST, DraftSection.CONFIRMED_CRITERIA)),
				DraftPageB.class);
	}

	private <T> T generatePage(String documentsText, String qnaText, String sections, Class<T> type) {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.DRAFT_PAGE_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of(
				"documents", documentsText, "qna", qnaText, "sections", sections));

		return chatClient.prompt()
				.options(OpenAiChatOptions.builder().model(analysisModel))
				.messages(List.of(systemMessage))
				.call()
				.entity(type);
	}

	private String generateBriefingSummary(HandoverDraftContent content) {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.BRIEFING_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of("draft", writeJson(content)));

		return chatClient.prompt()
				.messages(List.of(systemMessage))
				.call()
				.content();
	}

	private List<String> generateSuggestedQuestions(HandoverDraftContent content) {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.SUGGESTED_QUESTIONS_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of("draft", writeJson(content)));

		List<String> questions = chatClient.prompt()
				.messages(List.of(systemMessage))
				.call()
				.entity(new ParameterizedTypeReference<List<String>>() {
				});
		return questions == null ? List.of() : questions;
	}

	/** 대상 섹션만 다시 만든다. 결과에서 대상이 아닌 필드는 merge 때 버려진다. */
	private HandoverDraftContent generateSectionUpdate(String documentsText, HandoverDraftContent currentSections,
			String qnaText, Set<DraftSection> sections) {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.SECTION_UPDATE_SYSTEM_TEMPLATE);
		Message systemMessage = template.createMessage(Map.of(
				"documents", documentsText,
				"currentSections", writeJson(currentSections),
				"qna", qnaText,
				"sections", DraftSection.describe(sections)));

		HandoverDraftContent patch = chatClient.prompt()
				.options(OpenAiChatOptions.builder().model(analysisModel))
				.messages(List.of(systemMessage))
				.call()
				.entity(HandoverDraftContent.class);
		if (patch == null) {
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 문서 갱신 중 오류가 발생했습니다.");
		}
		return patch;
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

	/** needsAnswering: 답변 단계(ANSWERING)로 보낼지. 새 질문이 있거나, 질문이 없어도 아직 초안이 없으면 true. */
	public record AnalysisExecutionResult(int questionCount, boolean needsAnswering) {
	}

	private record AnalysisInput(String documentsText, List<String> askedQuestions) {
	}

	private record ApplyInput(String documentsText, HandoverDraftContent currentContent, List<QuestionSnapshot> questions) {
	}

	/** 트랜잭션 밖(AI 호출 중)에서 쓰는 질문 값 복사본. 저장 시점에 원본과 비교해 그 사이 바뀌었는지 확인한다. */
	private record QuestionSnapshot(UUID id, ClarificationQuestionType type, String questionText,
			ClarificationQuestionStatus status, String answer, List<DraftSection> targetSections, boolean needsApply) {

		static QuestionSnapshot from(ClarificationQuestion question) {
			return new QuestionSnapshot(question.getId(), question.getType(), question.getQuestionText(),
					question.getStatus(), question.getAnswer(), List.copyOf(question.getTargetSections()),
					question.needsApply());
		}

		boolean matches(ClarificationQuestion question) {
			return question.getStatus() == status && Objects.equals(question.getAnswer(), answer);
		}
	}
}
