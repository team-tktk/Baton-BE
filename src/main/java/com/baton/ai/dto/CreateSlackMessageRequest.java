package com.baton.ai.dto;

import java.time.Instant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSlackMessageRequest(
		@NotBlank @Size(max = 2048) String messageUrl,
		@NotBlank @Size(max = 255) String title,
		@NotBlank @Size(max = 255) String conversationName,
		@NotBlank @Size(max = 1000000) String content,
		Instant occurredAt,
		Boolean enabled) {}
