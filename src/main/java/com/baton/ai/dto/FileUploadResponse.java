package com.baton.ai.dto;

import java.util.UUID;

import com.baton.ai.SourceDocument;

public record FileUploadResponse(
		UUID sourceDocumentId,
		String fileName,
		String status) {

	public static FileUploadResponse from(SourceDocument sourceDocument) {
		return new FileUploadResponse(
				sourceDocument.getId(),
				sourceDocument.getFileName(),
				sourceDocument.getStatus().name());
	}
}
