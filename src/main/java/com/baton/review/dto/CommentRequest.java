package com.baton.review.dto;

import jakarta.validation.constraints.NotBlank;

public record CommentRequest(
		@NotBlank String content) {
}
