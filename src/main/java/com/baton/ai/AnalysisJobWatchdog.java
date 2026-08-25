package com.baton.ai;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 고착된 분석 작업을 주기적으로 찾아 FAILED로 강제 종료한다.
 *
 * 왜 필요한가: 분석은 @Async 스레드에서 실행되는데, 커밋 이후 이벤트가 유실되거나
 * (예: 작업 큐잉 직후 배포로 서버가 재시작되는 경우), OpenAI 호출이 응답 없이 멈추면
 * 작업이 QUEUED/GENERATING_QUESTIONS 등에 영원히 머무른다. AnalysisJobService.start()는
 * 진행 중인 작업이 하나라도 있으면 재시도조차 막아서, 이 상태가 되면 사용자는 영영
 * "분석 중" 화면에 갇힌다. 일정 시간 갱신이 없으면 실패로 강제 전환해 재시도를 열어준다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisJobWatchdog {

	private static final long STALE_AFTER_SECONDS = 180;

	private final AnalysisJobRepository analysisJobRepository;
	private final AnalysisJobService analysisJobService;

	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	public void failStaleJobs() {
		Instant threshold = Instant.now().minusSeconds(STALE_AFTER_SECONDS);
		List<AnalysisJob> staleJobs = analysisJobRepository.findByStatusInAndUpdatedAtBefore(
				AnalysisJobStatus.active(), threshold);

		for (AnalysisJob job : staleJobs) {
			log.warn("[*] Analysis job stale for over {}s, marking FAILED: jobId={}, handoverId={}, status={}",
					STALE_AFTER_SECONDS, job.getId(), job.getHandoverId(), job.getStatus());
			analysisJobService.fail(job.getId(), job.getHandoverId(),
					"처리 시간이 초과되어 자동으로 실패 처리되었습니다. 다시 시도해 주세요.");
		}
	}
}
