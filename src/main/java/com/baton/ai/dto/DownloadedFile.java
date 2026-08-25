package com.baton.ai.dto;

public record DownloadedFile(
		String fileName,
		String mimeType,
		byte[] content) {
}
