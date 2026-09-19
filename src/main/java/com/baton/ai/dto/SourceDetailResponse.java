package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentStatus;

/** AI 답변의 근거(citation)를 눌렀을 때 보여줄 원문 메타데이터. */
public record SourceDetailResponse(
		UUID sourceId,
		String title,
		String locator,
		Instant updatedAt,
		UUID fileId,
		String type,
		String url,
		String description,
		boolean enabled,
		String conversationName,
		Instant occurredAt,
		SourceDocumentStatus status) {

	public static SourceDetailResponse from(SourceDocument sourceDocument) {
		return new SourceDetailResponse(
				sourceDocument.getId(),
				sourceDocument.getFileName(),
				null,
				sourceDocument.getUpdatedAt(),
				sourceDocument.getSourceType() == com.baton.ai.SourceType.FILE ? sourceDocument.getId() : null,
				sourceDocument.getSourceType().name(),
				sourceDocument.getOriginalUrl(),
				sourceDocument.getDescription(),
				sourceDocument.isEnabled(),
				sourceDocument.getConversationName(),
				sourceDocument.getSourceOccurredAt(),
				sourceDocument.getStatus());
	}
}
