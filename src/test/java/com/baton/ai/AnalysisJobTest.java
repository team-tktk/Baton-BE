package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AnalysisJobTest {

	@Test
	void clampsProgressAndCompletes() {
		AnalysisJob job = AnalysisJob.create(UUID.randomUUID());

		job.updateProgress(AnalysisJobStatus.GENERATING_QUESTIONS, 150, "질문 생성 중");
		assertThat(job.getProgress()).isEqualTo(100);
		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.GENERATING_QUESTIONS);

		job.complete();
		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
		assertThat(job.getProgress()).isEqualTo(100);
	}

	@Test
	void terminalJobIgnoresLateProgressUpdate() {
		AnalysisJob job = AnalysisJob.create(UUID.randomUUID());
		job.fail("timeout");
		job.updateProgress(AnalysisJobStatus.GENERATING_DRAFT, 90, "초안 생성 중");

		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
		assertThat(job.getError()).isEqualTo("timeout");
	}
}
