package com.baton.masking.dto;

import java.util.List;
import java.util.UUID;

import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentStatus;
import com.baton.masking.MaskingCandidate;

/**
 * 파일 한 건의 마스킹 검수 화면 데이터.
 * text는 검수 대기(MASKING_REVIEW)일 때만 원문을 내려주고, 그 외 상태에서는 null이다.
 * 확정 이후에는 원문이 DB에 남지 않으므로 candidates의 preview로만 무엇을 가렸는지 보여준다.
 */
public record MaskingReviewResponse(
		UUID fileId,
		String fileName,
		String status,
		boolean confirmed,
		String text,
		MaskingSummary summary,
		List<MaskingCandidateResponse> candidates) {

	public static MaskingReviewResponse of(SourceDocument document, List<MaskingCandidate> candidates) {
		boolean inReview = document.getStatus() == SourceDocumentStatus.MASKING_REVIEW;
		return new MaskingReviewResponse(
				document.getId(),
				document.getFileName(),
				document.getStatus().name(),
				document.getMaskingConfirmedAt() != null,
				inReview ? document.getExtractedText() : null,
				MaskingSummary.of(candidates),
				candidates.stream().map(MaskingCandidateResponse::from).toList());
	}
}
