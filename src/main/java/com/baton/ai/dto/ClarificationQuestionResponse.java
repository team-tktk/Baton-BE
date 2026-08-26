package com.baton.ai.dto;

import java.util.List;
import java.util.UUID;

import com.baton.ai.ClarificationQuestion;
import com.baton.ai.ClarificationQuestionType;

public record ClarificationQuestionResponse(
		UUID id,
		ClarificationQuestionType type,
		String questionText,
		String reason,
		String evidence,
		List<QuestionOption> options,
		String status,
		String answer) {

	public static ClarificationQuestionResponse from(ClarificationQuestion question) {
		return new ClarificationQuestionResponse(
				question.getId(),
				question.getType(),
				question.getQuestionText(),
				question.getReason(),
				question.getEvidence(),
				question.getOptions(),
				question.getStatus().name(),
				question.getAnswer());
	}
}
