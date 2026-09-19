package com.baton.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWebLinkRequest(
		@NotBlank @Size(max = 2048) String url,
		@Size(max = 255) String title,
		@Size(max = 2000) String description,
		Boolean enabled) {}
