package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.ai.SourceDocument;

/** AI 답변의 근거(citation)를 눌렀을 때 보여줄 원문 메타데이터. */
public record SourceDetailResponse(
		UUID sourceId,
		String title,
		String locator,
		Instant updatedAt,
		UUID fileId) {

	public static SourceDetailResponse from(SourceDocument sourceDocument) {
		return new SourceDetailResponse(
				sourceDocument.getId(),
				sourceDocument.getFileName(),
				null,
				sourceDocument.getUpdatedAt(),
				sourceDocument.getId());
	}
}
