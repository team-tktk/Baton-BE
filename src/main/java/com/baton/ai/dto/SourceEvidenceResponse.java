package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentStatus;

/** AI가 참조할 수 있는 원문과 사용자가 원본을 열 수 있는 경로. */
public record SourceEvidenceResponse(
		UUID sourceId,
		String type,
		String title,
		String locator,
		Instant updatedAt,
		String accessPath,
		String description,
		String conversationName,
		Instant occurredAt,
		boolean enabled,
		SourceDocumentStatus status) {

	public static SourceEvidenceResponse from(UUID handoverId, SourceDocument sourceDocument) {
		boolean file = sourceDocument.getSourceType() == com.baton.ai.SourceType.FILE;
		return new SourceEvidenceResponse(
				sourceDocument.getId(),
				sourceDocument.getSourceType().name(),
				sourceDocument.getFileName(),
				file ? "업로드 원문" : sourceDocument.getConversationName(),
				sourceDocument.getUpdatedAt(),
				file ? "/api/v1/handovers/%s/files/%s/download".formatted(handoverId, sourceDocument.getId())
						: sourceDocument.getOriginalUrl(),
				sourceDocument.getDescription(),
				sourceDocument.getConversationName(),
				sourceDocument.getSourceOccurredAt(),
				sourceDocument.isEnabled(),
				sourceDocument.getStatus());
	}
}
