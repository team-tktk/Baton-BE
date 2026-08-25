package com.baton.ai;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 작업 생성 트랜잭션이 커밋된 뒤 실제 AI 분석을 별도 스레드에서 실행한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisJobDispatcher {

	private final AnalysisJobService analysisJobService;
	private final RagAnalysisService ragAnalysisService;

	@Async("analysisTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void dispatch(AnalysisJobRequested event) {
		try {
			analysisJobService.updateProgress(event.jobId(), AnalysisJobStatus.PARSING, 10, "업로드 문서 확인 중");
			analysisJobService.updateProgress(event.jobId(), AnalysisJobStatus.INDEXING, 30, "인덱싱 상태 확인 중");
			analysisJobService.updateProgress(event.jobId(), AnalysisJobStatus.GENERATING_QUESTIONS, 55, "보완 질문 생성 중");

			RagAnalysisService.AnalysisExecutionResult result = ragAnalysisService.analyze(event.handoverId());

			analysisJobService.updateProgress(event.jobId(), AnalysisJobStatus.GENERATING_DRAFT, 90, "인수인계 초안 저장 중");
			analysisJobService.complete(event.jobId(), event.handoverId(), result.questionCount() > 0);
		} catch (Exception e) {
			log.error("[*] Analysis job failed: jobId={}, handoverId={}", event.jobId(), event.handoverId(), e);
			analysisJobService.fail(event.jobId(), event.handoverId(), safeMessage(e));
		}
	}

	private String safeMessage(Exception e) {
		if (e instanceof com.baton.common.BusinessException businessException) {
			return businessException.getMessage();
		}
		return "AI 분석 중 오류가 발생했습니다.";
	}
}
