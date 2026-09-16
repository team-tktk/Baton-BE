package com.baton.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baton.masking.MaskingDetector.DetectedCandidate;

class MaskingCandidateTest {

	private static final UUID SOURCE_ID = UUID.randomUUID();
	private static final UUID HANDOVER_ID = UUID.randomUUID();

	@Test
	void autoDetectedCandidateStartsAppliedAndReviewed() {
		String text = "메일 minji.kim@example.com";
		MaskingCandidate candidate = MaskingCandidate.detected(SOURCE_ID, HANDOVER_ID,
				new DetectedCandidate(MaskingType.EMAIL, 3, text.length(), 0.98, false), text);

		assertThat(candidate.isApplied()).isTrue();
		assertThat(candidate.isPendingReview()).isFalse();
		assertThat(candidate.getOrigin()).isEqualTo(MaskingOrigin.DETECTED);
	}

	@Test
	void uncertainCandidateIsPendingUntilUserDecides() {
		String text = "번호 110-123-456789";
		MaskingCandidate candidate = MaskingCandidate.detected(SOURCE_ID, HANDOVER_ID,
				new DetectedCandidate(MaskingType.ACCOUNT, 3, text.length(), 0.6, true), text);

		assertThat(candidate.isApplied()).isTrue();
		assertThat(candidate.isPendingReview()).isTrue();

		candidate.decide(false);

		assertThat(candidate.isApplied()).isFalse();
		assertThat(candidate.isPendingReview()).isFalse();
	}

	@Test
	void manualCandidateIsAlreadyReviewed() {
		String text = "담당자 김민지 과장";
		MaskingCandidate candidate = MaskingCandidate.manual(SOURCE_ID, HANDOVER_ID, MaskingType.CUSTOM, 4, 7, text);

		assertThat(candidate.getOrigin()).isEqualTo(MaskingOrigin.MANUAL);
		assertThat(candidate.isApplied()).isTrue();
		assertThat(candidate.isPendingReview()).isFalse();
		assertThat(candidate.getPreview()).isEqualTo("김**");
	}

	@Test
	void overlapUsesHalfOpenRange() {
		String text = "0123456789";
		MaskingCandidate candidate = MaskingCandidate.manual(SOURCE_ID, HANDOVER_ID, MaskingType.CUSTOM, 2, 5, text);

		assertThat(candidate.overlaps(4, 6)).isTrue();
		assertThat(candidate.overlaps(5, 7)).isFalse();
		assertThat(candidate.overlaps(0, 2)).isFalse();
	}

	@Test
	void previewHidesAllButLastFourDigits() {
		assertThat(MaskingPreview.of(MaskingType.PHONE, "010-1234-5678")).isEqualTo("***-****-5678");
		assertThat(MaskingPreview.of(MaskingType.ACCOUNT, "110-123-456789")).isEqualTo("***-***-**6789");
		assertThat(MaskingPreview.of(MaskingType.RRN, "900101-1234568")).isEqualTo("******-***4568");
	}

	@Test
	void previewKeepsEmailDomain() {
		assertThat(MaskingPreview.of(MaskingType.EMAIL, "minji.kim@example.com")).isEqualTo("min***@example.com");
		assertThat(MaskingPreview.of(MaskingType.EMAIL, "ab@example.com")).isEqualTo("ab***@example.com");
	}
}
