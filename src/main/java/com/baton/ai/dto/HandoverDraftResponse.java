package com.baton.ai.dto;

import java.time.Instant;

import com.baton.ai.HandoverDraft;

public record HandoverDraftResponse(
		HandoverDraftContent content,
		Instant updatedAt) {

	public static HandoverDraftResponse from(HandoverDraft handoverDraft) {
		return new HandoverDraftResponse(handoverDraft.getContent(), handoverDraft.getUpdatedAt());
	}
}
