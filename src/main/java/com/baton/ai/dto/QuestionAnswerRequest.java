package com.baton.ai.dto;

import com.baton.ai.ClarificationQuestionStatus;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * 확인 질문 처리. status로 답변·모름·해당 없음·나중에 답하기를 구분한다.
 * answer는 ANSWERED일 때만 필수이고, 나머지 상태에서는 보내면 안 된다.
 */
public record QuestionAnswerRequest(
		@NotNull ClarificationQuestionStatus status,
		String answer) {

	@AssertTrue(message = "ANSWERED는 답변이 필요하고, UNKNOWN·NOT_APPLICABLE·DEFERRED는 답변 없이 보내야 합니다. PENDING으로는 바꿀 수 없습니다.")
	public boolean isValidCombination() {
		if (status == null) {
			return true;
		}
		boolean hasAnswer = answer != null && !answer.isBlank();
		return switch (status) {
			case ANSWERED -> hasAnswer;
			case UNKNOWN, NOT_APPLICABLE, DEFERRED -> !hasAnswer;
			case PENDING -> false;
		};
	}
}
