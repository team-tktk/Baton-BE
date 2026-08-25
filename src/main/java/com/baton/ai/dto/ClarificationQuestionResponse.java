package com.baton.ai.dto;

import java.util.List;
import java.util.UUID;

import com.baton.ai.ClarificationQuestion;

public record ClarificationQuestionResponse(
		UUID id,
		String questionText,
		String reason,
		List<QuestionOption> options,
		String status,
		String answer) {

	public static ClarificationQuestionResponse from(ClarificationQuestion question) {
		return new ClarificationQuestionResponse(
				question.getId(),
				question.getQuestionText(),
				question.getReason(),
				question.getOptions(),
				question.getStatus().name(),
				question.getAnswer());
	}
}
