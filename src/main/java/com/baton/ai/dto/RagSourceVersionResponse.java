package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;
import com.baton.ai.RagSourceVersion;

public record RagSourceVersionResponse(
		UUID id,
		UUID sourceId,
		String type,
		String title,
		String locator,
		String content,
		Long revision,
		Instant sourceUpdatedAt,
		Instant archivedAt) {
	public static RagSourceVersionResponse from(RagSourceVersion version) {
		return new RagSourceVersionResponse(version.getId(), version.getSourceId(), version.getSourceType(),
				version.getTitle(), version.getLocator(), version.getContent(), version.getSourceRevision(),
				version.getSourceUpdatedAt(), version.getArchivedAt());
	}
}
