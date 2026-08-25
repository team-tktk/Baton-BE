package com.baton.handover.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.handover.Handover;
import com.baton.handover.HandoverParticipant;
import com.baton.handover.HandoverStatus;
import com.baton.handover.ParticipantRole;

/**
 * 목록 화면용 인수인계 요약 한 건. 상세(HandoverResponse)보다 가벼워 카운트만 노출한다.
 * receiptStatus는 받은 목록에서만 채워지고(현재 사용자의 수신 상태), 보낸 목록에서는 null.
 */
public record HandoverSummaryResponse(
		UUID id,
		String title,
		HandoverStatus status,
		UUID ownerId,
		int recipientCount,
		int workScopeCount,
		String receiptStatus,
		Instant submittedAt,
		Instant createdAt,
		Instant updatedAt) {

	/** 보낸 목록용 — 수신 상태는 의미 없어 null. */
	public static HandoverSummaryResponse ofSent(Handover h) {
		return build(h, null);
	}

	/** 받은 목록용 — 현재 인수자의 수신 상태를 함께 담는다. */
	public static HandoverSummaryResponse ofReceived(Handover h, UUID viewerId) {
		String receipt = h.getParticipants().stream()
				.filter(p -> p.getRole() == ParticipantRole.RECIPIENT && p.getUserId().equals(viewerId))
				.map(HandoverParticipant::getReceiptStatus)
				.filter(s -> s != null)
				.map(Enum::name)
				.findFirst()
				.orElse(null);
		return build(h, receipt);
	}

	private static HandoverSummaryResponse build(Handover h, String receiptStatus) {
		int recipientCount = (int) h.getParticipants().stream()
				.filter(p -> p.getRole() == ParticipantRole.RECIPIENT)
				.count();
		return new HandoverSummaryResponse(
				h.getId(),
				h.getTitle(),
				h.getStatus(),
				h.getOwnerId(),
				recipientCount,
				h.getWorkScopes().size(),
				receiptStatus,
				h.getSubmittedAt(),
				h.getCreatedAt(),
				h.getUpdatedAt());
	}
}
