package com.baton.ai.dto;

import java.time.Instant;
import jakarta.validation.constraints.Size;

public record UpdateExternalSourceRequest(
		@Size(max = 255) String title,
		@Size(max = 2000) String description,
		@Size(max = 2048) String url,
		@Size(max = 255) String conversationName,
		@Size(max = 1000000) String content,
		Instant occurredAt,
		Boolean enabled) {}
