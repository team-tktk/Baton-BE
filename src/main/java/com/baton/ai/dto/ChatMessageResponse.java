package com.baton.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.baton.ai.ChatMessage;

public record ChatMessageResponse(
		UUID id,
		UUID askedBy,
		String question,
		String answer,
		boolean grounded,
		AnswerSource answerSource,
		List<Citation> citations,
		Instant createdAt) {

	public static ChatMessageResponse from(ChatMessage message) {
		return new ChatMessageResponse(
				message.getId(),
				message.getAskedBy(),
				message.getQuestion(),
				message.getAnswer(),
				message.isGrounded(),
				resolveAnswerSource(message),
				message.getCitations(),
				message.getCreatedAt());
	}

	private static AnswerSource resolveAnswerSource(ChatMessage message) {
		if (message.isGrounded()) {
			return AnswerSource.DOCUMENT;
		}
		return message.getAnswer() == null ? AnswerSource.NOT_FOUND : AnswerSource.GENERAL_KNOWLEDGE;
	}
}
