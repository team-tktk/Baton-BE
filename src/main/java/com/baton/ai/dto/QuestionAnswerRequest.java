package com.baton.ai.dto;

import jakarta.validation.constraints.AssertTrue;

public record QuestionAnswerRequest(
		String answer,
		boolean skipped) {

	@AssertTrue(message = "건너뛰지 않을 때는 답변을 입력해야 하며, 건너뛸 때는 답변을 함께 보낼 수 없습니다.")
	public boolean isValidCombination() {
		boolean hasAnswer = answer != null && !answer.isBlank();
		return skipped ? !hasAnswer : hasAnswer;
	}
}
