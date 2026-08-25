package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.ai.SourceDocument;

public record FileMetadataResponse(
		UUID id,
		String fileName,
		String mimeType,
		long size,
		String status,
		Instant createdAt) {

	public static FileMetadataResponse from(SourceDocument sourceDocument) {
		return new FileMetadataResponse(
				sourceDocument.getId(),
				sourceDocument.getFileName(),
				sourceDocument.getMimeType(),
				sourceDocument.getFileSize(),
				sourceDocument.getStatus().name(),
				sourceDocument.getCreatedAt());
	}
}
