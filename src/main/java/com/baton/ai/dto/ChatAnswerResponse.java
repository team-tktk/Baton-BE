package com.baton.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatAnswerResponse(
		UUID messageId,
		String answer,
		boolean grounded,
		List<Citation> citations,
		String fallbackContact,
		Instant answeredAt) {

	public static ChatAnswerResponse notFound() {
		return new ChatAnswerResponse(
				UUID.randomUUID(),
				null,
				false,
				List.of(),
				"업로드된 문서에서 답을 찾지 못했습니다. 인계자에게 직접 문의해주세요.",
				Instant.now());
	}

	public static ChatAnswerResponse of(String answer, List<Citation> citations) {
		return new ChatAnswerResponse(
				UUID.randomUUID(),
				answer,
				true,
				citations,
				null,
				Instant.now());
	}
}
