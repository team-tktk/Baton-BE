package com.baton.handover.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.baton.handover.Handover;
import com.baton.handover.HandoverParticipant;
import com.baton.handover.HandoverStatus;
import com.baton.handover.ParticipantRole;
import com.baton.handover.WorkScope;

/**
 * 인수인계 상세 응답. 헤더 정보 + 참여자/업무범위 + "지금 요청한 사용자"의 역할(viewerRole)을 함께 내려
 * 프론트가 화면 권한 분기를 서버 판정 그대로 쓰게 한다.
 */
public record HandoverResponse(
		UUID id,
		String title,
		HandoverStatus status,
		UUID ownerId,
		String viewerRole,           // 요청자 기준: OWNER | RECIPIENT | REVIEWER
		List<ParticipantDto> participants,
		List<WorkScopeDto> workScopes,
		Instant submittedAt,
		Instant completedAt,
		Instant createdAt,
		Instant updatedAt) {

	public record ParticipantDto(UUID userId, ParticipantRole role, String receiptStatus) {
		static ParticipantDto from(HandoverParticipant p) {
			return new ParticipantDto(p.getUserId(), p.getRole(),
					p.getReceiptStatus() == null ? null : p.getReceiptStatus().name());
		}
	}

	public record WorkScopeDto(UUID id, String title, String description) {
		static WorkScopeDto from(WorkScope w) {
			return new WorkScopeDto(w.getId(), w.getTitle(), w.getDescription());
		}
	}

	public static HandoverResponse of(Handover h, UUID viewerId) {
		return new HandoverResponse(
				h.getId(),
				h.getTitle(),
				h.getStatus(),
				h.getOwnerId(),
				resolveViewerRole(h, viewerId),
				h.getParticipants().stream().map(ParticipantDto::from).toList(),
				h.getWorkScopes().stream().map(WorkScopeDto::from).toList(),
				h.getSubmittedAt(),
				h.getCompletedAt(),
				h.getCreatedAt(),
				h.getUpdatedAt());
	}

	private static String resolveViewerRole(Handover h, UUID viewerId) {
		if (h.isOwner(viewerId)) {
			return "OWNER";
		}
		if (h.hasParticipant(viewerId, ParticipantRole.REVIEWER)) {
			return ParticipantRole.REVIEWER.name();
		}
		if (h.hasParticipant(viewerId, ParticipantRole.RECIPIENT)) {
			return ParticipantRole.RECIPIENT.name();
		}
		return null;
	}
}
