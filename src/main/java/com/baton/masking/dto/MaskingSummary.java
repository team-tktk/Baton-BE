package com.baton.masking.dto;

import java.util.List;
import java.util.function.Predicate;

import com.baton.masking.MaskingCandidate;
import com.baton.masking.MaskingOrigin;

/**
 * 검수 화면 상단 카드용 집계.
 * - autoMasked: 자동으로 찾았고 확인이 필요 없는 항목("자동 마스킹 N건")
 * - needsReview: 확인이 필요한 항목 전체("확인 필요 N건")
 * - remaining: 확인이 필요한데 아직 적용/해제를 누르지 않은 항목. 0이어야 확정할 수 있다.
 * - applied: 확정 시 실제로 가려질 항목
 */
public record MaskingSummary(
		int total,
		int autoMasked,
		int needsReview,
		int remaining,
		int applied) {

	public static MaskingSummary of(List<MaskingCandidate> candidates) {
		return new MaskingSummary(
				candidates.size(),
				count(candidates, c -> c.getOrigin() == MaskingOrigin.DETECTED && !c.isNeedsReview()),
				count(candidates, MaskingCandidate::isNeedsReview),
				count(candidates, MaskingCandidate::isPendingReview),
				count(candidates, MaskingCandidate::isApplied));
	}

	private static int count(List<MaskingCandidate> candidates, Predicate<MaskingCandidate> filter) {
		return (int) candidates.stream().filter(filter).count();
	}
}
