package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.baton.auth.UserRepository;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 인수인계서 생성(확인 질문 완료)은 확인 질문 단계에서만, 검증을 모두 통과한 뒤에만 AI 사용량을 차감한다. */
@ExtendWith(MockitoExtension.class)
class RagAnalysisServiceCompleteTest {

	@Mock
	private SourceDocumentRepository sourceDocumentRepository;
	@Mock
	private HandoverDraftRepository handoverDraftRepository;
	@Mock
	private ClarificationQuestionRepository clarificationQuestionRepository;
	@Mock
	private ChatClient chatClient;
	@Mock
	private UserRepository userRepository;
	@Mock
	private HandoverRepository handoverRepository;
	@Mock
	private PlatformTransactionManager transactionManager;

	private RagAnalysisService service;
	private final UUID handoverId = UUID.randomUUID();
	private final Handover handover = Handover.create(UUID.randomUUID(), "운영 업무 인수인계");
	private final AtomicInteger charged = new AtomicInteger();

	@BeforeEach
	void setUp() {
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new RagAnalysisService(sourceDocumentRepository, handoverDraftRepository,
				clarificationQuestionRepository, chatClient, new ObjectMapper(), userRepository,
				new TransactionTemplate(transactionManager), handoverRepository);
		when(handoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));
	}

	@Test
	void rejectsRegenerationAfterDraftWasCreated() {
		handover.markAnalysisStarted();
		handover.markAnalysisCompleted(false); // EDITING

		assertThatThrownBy(() -> service.completeQuestions(handoverId, charged::incrementAndGet))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.HANDOVER_INVALID_STATE));

		assertThat(charged).hasValue(0);
		verifyNoInteractions(chatClient);
	}

	@Test
	void doesNotChargeWhenQuestionsArePending() {
		handover.markAnalysisStarted();
		handover.markAnalysisCompleted(true); // ANSWERING
		when(clarificationQuestionRepository.findAllByHandoverId(handoverId)).thenReturn(List.of(
				ClarificationQuestion.create(handoverId, ClarificationQuestionType.INTERVIEW, "질문", "이유", null, List.of())));

		assertThatThrownBy(() -> service.completeQuestions(handoverId, charged::incrementAndGet))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUESTIONS_INCOMPLETE));

		assertThat(charged).hasValue(0);
		verifyNoInteractions(chatClient);
	}
}
