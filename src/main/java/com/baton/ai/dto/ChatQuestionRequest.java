package com.baton.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatQuestionRequest(
		@NotBlank(message = "질문을 입력해주세요.")
		String question) {
}
