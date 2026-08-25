package com.baton.handover.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.auth.dto.UserSummaryResponse;
import com.baton.handover.Handover;
import com.baton.handover.HandoverParticipant;
import com.baton.handover.HandoverStatus;
import com.baton.handover.ParticipantRole;

/**
 * 목록 화면용 인수인계 요약 한 건. 상세(HandoverResponse)보다 가볍지만,
 * 프론트 목록이 별도 조회 없이 카드를 그릴 수 있도록 보낸 사람(owner) 요약·대표 업무명·개수를 함께 담는다.
 * receiptStatus는 받은 목록에서만 채워지고(현재 사용자의 수신 상태), 보낸/검토 목록에서는 null.
 */
public record HandoverSummaryResponse(
		UUID id,
		String title,
		HandoverStatus status,
		UserSummaryResponse owner,   // 보낸 사람 요약(이름·팀·직책). 목록에서 사람 정보를 바로 표시.
		String workScopeSummary,     // 대표 업무명 = 첫 번째 업무범위 제목(없으면 null)
		int workScopeCount,
		int fileCount,               // 첨부 파일 개수
		int recipientCount,
		String receiptStatus,        // 받은 목록: 현재 인수자의 UNREAD/READ. 그 외 목록: null
		Instant submittedAt,
		Instant createdAt,
		Instant updatedAt) {

	/** 보낸/검토 목록용 — 수신 상태는 의미 없어 null. */
	public static HandoverSummaryResponse ofSent(Handover h, UserSummaryResponse owner, int fileCount) {
		return build(h, owner, fileCount, null);
	}

	/** 받은 목록용 — 현재 인수자의 수신 상태를 함께 담는다. */
	public static HandoverSummaryResponse ofReceived(Handover h, UUID viewerId, UserSummaryResponse owner, int fileCount) {
		String receipt = h.getParticipants().stream()
				.filter(p -> p.getRole() == ParticipantRole.RECIPIENT && p.getUserId().equals(viewerId))
				.map(HandoverParticipant::getReceiptStatus)
				.filter(s -> s != null)
				.map(Enum::name)
				.findFirst()
				.orElse(null);
		return build(h, owner, fileCount, receipt);
	}

	private static HandoverSummaryResponse build(Handover h, UserSummaryResponse owner, int fileCount, String receiptStatus) {
		int recipientCount = (int) h.getParticipants().stream()
				.filter(p -> p.getRole() == ParticipantRole.RECIPIENT)
				.count();
		String workScopeSummary = h.getWorkScopes().isEmpty() ? null : h.getWorkScopes().get(0).getTitle();
		return new HandoverSummaryResponse(
				h.getId(),
				h.getTitle(),
				h.getStatus(),
				owner,
				workScopeSummary,
				h.getWorkScopes().size(),
				fileCount,
				recipientCount,
				receiptStatus,
				h.getSubmittedAt(),
				h.getCreatedAt(),
				h.getUpdatedAt());
	}
}
