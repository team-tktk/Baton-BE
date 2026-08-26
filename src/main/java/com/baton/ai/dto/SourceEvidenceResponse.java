package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.ai.SourceDocument;

/** AI가 참조할 수 있는 원문과 사용자가 원본을 열 수 있는 경로. */
public record SourceEvidenceResponse(
		UUID sourceId,
		String type,
		String title,
		String locator,
		Instant updatedAt,
		String accessPath) {

	public static SourceEvidenceResponse from(UUID handoverId, SourceDocument sourceDocument) {
		return new SourceEvidenceResponse(
				sourceDocument.getId(),
				"DOCUMENT",
				sourceDocument.getFileName(),
				"업로드 원문",
				sourceDocument.getUpdatedAt(),
				"/api/v1/handovers/%s/files/%s/download".formatted(handoverId, sourceDocument.getId()));
	}
}
