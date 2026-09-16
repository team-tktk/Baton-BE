package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.ai.SourceDocument;

/**
 * remainingReviewCount: 마스킹 검수에서 확인이 필요한데 아직 적용/해제하지 않은 항목 수.
 * 모든 파일이 0이고 MASKING_REVIEW가 아니어야(또는 검수를 확정해야) 분석을 진행할 수 있다.
 */
public record FileMetadataResponse(
		UUID id,
		String fileName,
		String mimeType,
		long size,
		String status,
		long remainingReviewCount,
		Instant createdAt) {

	/** 검수 정보가 필요 없는 화면(제출 이후의 관리자 검토 등)용. 남은 확인 개수는 0으로 둔다. */
	public static FileMetadataResponse from(SourceDocument sourceDocument) {
		return from(sourceDocument, 0);
	}

	public static FileMetadataResponse from(SourceDocument sourceDocument, long remainingReviewCount) {
		return new FileMetadataResponse(
				sourceDocument.getId(),
				sourceDocument.getFileName(),
				sourceDocument.getMimeType(),
				sourceDocument.getFileSize(),
				sourceDocument.getStatus().name(),
				remainingReviewCount,
				sourceDocument.getCreatedAt());
	}
}
