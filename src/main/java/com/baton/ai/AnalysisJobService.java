package com.baton.ai;

import java.util.EnumSet;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.dto.AnalysisJobResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverRepository;

import lombok.RequiredArgsConstructor;

/** 분석 작업 생성, 진행 상태 갱신, 중복 실행 및 재시도 규칙을 담당한다. */
@Service
@RequiredArgsConstructor
public class AnalysisJobService {

	private static final EnumSet<AnalysisJobStatus> ACTIVE_STATUSES = AnalysisJobStatus.active();

	private final AnalysisJobRepository analysisJobRepository;
	private final HandoverRepository handoverRepository;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public AnalysisJobResponse start(UUID handoverId, boolean retry) {
		Handover handover = handoverRepository.findByIdForUpdate(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));

		if (analysisJobRepository.existsByHandoverIdAndStatusIn(handoverId, ACTIVE_STATUSES)) {
			throw new BusinessException(ErrorCode.AI_ANALYSIS_ALREADY_RUNNING);
		}

		if (retry) {
			AnalysisJob latest = loadLatest(handoverId);
			if (latest.getStatus() != AnalysisJobStatus.FAILED) {
				throw new BusinessException(ErrorCode.AI_ANALYSIS_RETRY_NOT_ALLOWED);
			}
		}

		if (!handover.canStartAnalysis()) {
			throw new BusinessException(ErrorCode.HANDOVER_INVALID_STATE, "현재 상태에서는 분석을 시작할 수 없습니다: " + handover.getStatus());
		}

		handover.markAnalysisStarted();
		AnalysisJob job = analysisJobRepository.save(AnalysisJob.create(handoverId));
		eventPublisher.publishEvent(new AnalysisJobRequested(job.getId(), handoverId));
		return AnalysisJobResponse.from(job);
	}

	@Transactional(readOnly = true)
	public AnalysisJobResponse getLatest(UUID handoverId) {
		return AnalysisJobResponse.from(loadLatest(handoverId));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProgress(UUID jobId, AnalysisJobStatus status, int progress, String currentStep) {
		AnalysisJob job = load(jobId);
		job.updateProgress(status, progress, currentStep);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(UUID jobId, UUID handoverId, boolean hasQuestions) {
		AnalysisJob job = load(jobId);
		job.complete();
		Handover handover = handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
		handover.markAnalysisCompleted(hasQuestions);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(UUID jobId, UUID handoverId, String error) {
		AnalysisJob job = load(jobId);
		job.fail(error);
		handoverRepository.findById(handoverId).ifPresent(Handover::markAnalysisFailed);
	}

	private AnalysisJob loadLatest(UUID handoverId) {
		return analysisJobRepository.findFirstByHandoverIdOrderByCreatedAtDesc(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_ANALYSIS_JOB_NOT_FOUND));
	}

	private AnalysisJob load(UUID jobId) {
		return analysisJobRepository.findById(jobId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_ANALYSIS_JOB_NOT_FOUND));
	}
}
