package com.baton.ai.dto;

public record QuestionAnswerRequest(
		String answer,
		boolean skipped) {
}
