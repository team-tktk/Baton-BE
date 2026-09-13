package com.baton.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatQuestionRequest(
		@NotBlank(message = "질문을 입력해주세요.")
		// 과도하게 긴 입력(비용 폭탄)과 긴 프롬프트 인젝션 시도를 1차로 차단한다.
		@Size(max = 1000, message = "질문은 1000자 이내로 입력해주세요.")
		String question) {
}
