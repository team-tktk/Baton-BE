package com.baton.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.baton.ai.ChatMessage;

public record ChatAnswerResponse(
		UUID messageId,
		String answer,
		boolean grounded,
		List<Citation> citations,
		String fallbackContact,
		Instant answeredAt) {

	private static final String FALLBACK_CONTACT = "업로드된 문서에서 답을 찾지 못했습니다. 인계자에게 직접 문의해주세요.";

	/** 실제로 저장된 ChatMessage 기준으로 응답을 만든다 — 응답의 messageId가 이력 조회 결과와 항상 같은 값이 되도록. */
	public static ChatAnswerResponse from(ChatMessage message) {
		return new ChatAnswerResponse(
				message.getId(),
				message.getAnswer(),
				message.isGrounded(),
				message.getCitations(),
				message.isGrounded() ? null : FALLBACK_CONTACT,
				message.getCreatedAt());
	}
}
