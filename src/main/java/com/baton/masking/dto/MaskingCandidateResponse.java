package com.baton.masking.dto;

import java.util.UUID;

import com.baton.masking.MaskingCandidate;

/**
 * 검수 화면 목록의 한 줄. startOffset~endOffset은 MaskingReviewResponse.text 기준 [start, end) 구간이다.
 * pendingReview가 true인 항목이 "확인 필요"이며, 적용/해제 중 하나를 누르면 false가 된다.
 */
public record MaskingCandidateResponse(
		UUID id,
		String type,
		String typeLabel,
		String origin,
		int startOffset,
		int endOffset,
		int confidencePercent,
		boolean applied,
		boolean needsReview,
		boolean pendingReview,
		String preview) {

	public static MaskingCandidateResponse from(MaskingCandidate candidate) {
		return new MaskingCandidateResponse(
				candidate.getId(),
				candidate.getType().name(),
				candidate.getType().getLabel(),
				candidate.getOrigin().name(),
				candidate.getStartOffset(),
				candidate.getEndOffset(),
				(int) Math.round(candidate.getConfidence() * 100),
				candidate.isApplied(),
				candidate.isNeedsReview(),
				candidate.isPendingReview(),
				candidate.getPreview());
	}
}
