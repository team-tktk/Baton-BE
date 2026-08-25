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
		List<Citation> citations,
		Instant createdAt) {

	public static ChatMessageResponse from(ChatMessage message) {
		return new ChatMessageResponse(
				message.getId(),
				message.getAskedBy(),
				message.getQuestion(),
				message.getAnswer(),
				message.isGrounded(),
				message.getCitations(),
				message.getCreatedAt());
	}
}
