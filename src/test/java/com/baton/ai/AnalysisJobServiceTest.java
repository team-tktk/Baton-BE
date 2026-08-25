package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverRepository;
import com.baton.handover.HandoverStatus;

@ExtendWith(MockitoExtension.class)
class AnalysisJobServiceTest {

	@Mock
	private AnalysisJobRepository analysisJobRepository;
	@Mock
	private HandoverRepository handoverRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private AnalysisJobService service;
	private UUID handoverId;
	private Handover handover;

	@BeforeEach
	void setUp() {
		service = new AnalysisJobService(analysisJobRepository, handoverRepository, eventPublisher);
		handoverId = UUID.randomUUID();
		handover = Handover.create(UUID.randomUUID(), "운영 업무 인수인계");
		when(handoverRepository.findByIdForUpdate(handoverId)).thenReturn(Optional.of(handover));
	}

	@Test
	void createsQueuedJobAndMovesHandoverToAnalyzing() {
		when(analysisJobRepository.existsByHandoverIdAndStatusIn(any(), anyCollection())).thenReturn(false);
		when(analysisJobRepository.save(any(AnalysisJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

		var response = service.start(handoverId, false);

		assertThat(response.status()).isEqualTo(AnalysisJobStatus.QUEUED.name());
		assertThat(handover.getStatus()).isEqualTo(HandoverStatus.ANALYZING);
		verify(eventPublisher).publishEvent(any(AnalysisJobRequested.class));
	}

	@Test
	void rejectsDuplicateActiveJob() {
		when(analysisJobRepository.existsByHandoverIdAndStatusIn(any(), anyCollection())).thenReturn(true);

		assertThatThrownBy(() -> service.start(handoverId, false))
				.isInstanceOfSatisfying(BusinessException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_ANALYSIS_ALREADY_RUNNING));

		verify(analysisJobRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void retriesOnlyLatestFailedJob() {
		AnalysisJob completed = AnalysisJob.create(handoverId);
		completed.complete();
		when(analysisJobRepository.existsByHandoverIdAndStatusIn(any(), anyCollection())).thenReturn(false);
		when(analysisJobRepository.findFirstByHandoverIdOrderByCreatedAtDesc(handoverId))
				.thenReturn(Optional.of(completed));

		assertThatThrownBy(() -> service.start(handoverId, true))
				.isInstanceOfSatisfying(BusinessException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_ANALYSIS_RETRY_NOT_ALLOWED));
	}
}
