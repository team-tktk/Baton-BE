package com.baton.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.baton.ai.ClarificationQuestionStatus;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class QuestionAnswerRequestTest {

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		validatorFactory.close();
	}

	@Test
	void acceptsDirectAnswer() {
		assertThat(validator.validate(new QuestionAnswerRequest(ClarificationQuestionStatus.ANSWERED, "팀장에게 먼저 확인"))).isEmpty();
	}

	@Test
	void acceptsUnknownNotApplicableAndDeferredWithoutAnswer() {
		assertThat(validator.validate(new QuestionAnswerRequest(ClarificationQuestionStatus.UNKNOWN, null))).isEmpty();
		assertThat(validator.validate(new QuestionAnswerRequest(ClarificationQuestionStatus.NOT_APPLICABLE, null))).isEmpty();
		assertThat(validator.validate(new QuestionAnswerRequest(ClarificationQuestionStatus.DEFERRED, null))).isEmpty();
	}

	@Test
	void rejectsInvalidCombinations() {
		assertThat(validator.validate(new QuestionAnswerRequest(ClarificationQuestionStatus.ANSWERED, " "))).isNotEmpty();
		assertThat(validator.validate(new QuestionAnswerRequest(ClarificationQuestionStatus.UNKNOWN, "답변"))).isNotEmpty();
		assertThat(validator.validate(new QuestionAnswerRequest(ClarificationQuestionStatus.PENDING, null))).isNotEmpty();
		assertThat(validator.validate(new QuestionAnswerRequest(null, "답변"))).isNotEmpty();
	}
}
