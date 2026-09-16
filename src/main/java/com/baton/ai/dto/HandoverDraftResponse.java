package com.baton.ai.dto;

import java.time.Instant;

import com.baton.ai.HandoverDraft;

/** revision: 문서 버전. 수정·보완안 적용 요청에 그대로 돌려보내면 그사이 바뀐 문서를 덮어쓰지 않는다. */
public record HandoverDraftResponse(
		HandoverDraftContent content,
		long revision,
		Instant updatedAt) {

	public static HandoverDraftResponse from(HandoverDraft handoverDraft) {
		return new HandoverDraftResponse(
				handoverDraft.getContent(), handoverDraft.getRevision(), handoverDraft.getUpdatedAt());
	}
}
