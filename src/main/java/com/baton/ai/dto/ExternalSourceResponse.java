package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;
import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentStatus;
import com.baton.ai.SourceType;

public record ExternalSourceResponse(UUID sourceId, SourceType type, String title, String description,
		String url, String conversationName, Instant occurredAt, boolean enabled,
		SourceDocumentStatus status, Instant createdAt, Instant updatedAt, boolean fetched) {
	public static ExternalSourceResponse from(SourceDocument source, boolean fetched) {
		return new ExternalSourceResponse(source.getId(), source.getSourceType(), source.getFileName(),
				source.getDescription(), source.getOriginalUrl(), source.getConversationName(),
				source.getSourceOccurredAt(), source.isEnabled(), source.getStatus(), source.getCreatedAt(),
				source.getUpdatedAt(), fetched);
	}
}
