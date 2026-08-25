package com.baton.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
		assertThat(validator.validate(new QuestionAnswerRequest("팀장에게 먼저 확인", false))).isEmpty();
	}

	@Test
	void acceptsSkipWithoutAnswer() {
		assertThat(validator.validate(new QuestionAnswerRequest(null, true))).isEmpty();
	}

	@Test
	void rejectsMissingAnswerAndSkipWithAnswer() {
		assertThat(validator.validate(new QuestionAnswerRequest(" ", false))).isNotEmpty();
		assertThat(validator.validate(new QuestionAnswerRequest("답변", true))).isNotEmpty();
	}
}
