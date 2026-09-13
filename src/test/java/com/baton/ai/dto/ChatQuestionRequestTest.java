package com.baton.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * 챗봇 질문 입력 검증. 과도하게 긴 입력(비용 폭탄·긴 프롬프트 인젝션 시도)을 1차로 막는
 * 길이 제한(@Size max=1000)이 실제로 걸리는지 못박아 둔다.
 */
class ChatQuestionRequestTest {

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
	void acceptsNormalQuestion() {
		assertThat(validator.validate(new ChatQuestionRequest("배송업체 회신은 언제까지 기다려요?"))).isEmpty();
	}

	@Test
	void acceptsQuestionAtMaxLength() {
		assertThat(validator.validate(new ChatQuestionRequest("가".repeat(1000)))).isEmpty();
	}

	@Test
	void rejectsBlankQuestion() {
		assertThat(validator.validate(new ChatQuestionRequest(" "))).isNotEmpty();
	}

	@Test
	void rejectsTooLongQuestion() {
		assertThat(validator.validate(new ChatQuestionRequest("가".repeat(1001)))).isNotEmpty();
	}
}
