package com.baton.handover;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class HandoverAnalysisStateTest {

	@Test
	void movesThroughAnalysisAndQuestionStates() {
		Handover handover = Handover.create(UUID.randomUUID(), "운영 업무 인수인계");

		assertThat(handover.canStartAnalysis()).isTrue();
		assertThat(handover.isSubmittable()).isFalse();

		handover.markAnalysisStarted();
		assertThat(handover.getStatus()).isEqualTo(HandoverStatus.ANALYZING);

		handover.markAnalysisCompleted(true);
		assertThat(handover.getStatus()).isEqualTo(HandoverStatus.ANSWERING);

		handover.markQuestionsCompleted();
		assertThat(handover.getStatus()).isEqualTo(HandoverStatus.EDITING);
		assertThat(handover.isSubmittable()).isTrue();
	}

	@Test
	void returnsToDraftAfterAnalysisFailure() {
		Handover handover = Handover.create(UUID.randomUUID(), "운영 업무 인수인계");
		handover.markAnalysisStarted();
		handover.markAnalysisFailed();

		assertThat(handover.getStatus()).isEqualTo(HandoverStatus.DRAFT);
		assertThat(handover.canStartAnalysis()).isTrue();
	}
}
