package com.baton.handover.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baton.auth.dto.UserSummaryResponse;
import com.baton.handover.Handover;
import com.baton.handover.HandoverParticipant;
import com.baton.handover.HandoverStatus;
import com.baton.handover.ParticipantRole;
import com.baton.handover.WorkScope;

/**
 * 인수인계 상세 응답. 헤더 정보 + 참여자/업무범위 + "지금 요청한 사용자"의 역할(viewerRole)을 함께 내려
 * 프론트가 화면 권한 분기를 서버 판정 그대로 쓰게 한다.
 *
 * owner와 participants는 userId만이 아니라 이름·팀·직책(UserSummaryResponse)까지 담아
 * 프론트가 별도 사용자 조회 없이 인계자/인수자/관리자를 표시할 수 있게 한다.
 */
public record HandoverResponse(
		UUID id,
		String title,
		HandoverStatus status,
		UserSummaryResponse owner,   // 인계자 요약(이름·팀·직책)
		String viewerRole,           // 요청자 기준: OWNER | RECIPIENT | REVIEWER
		List<ParticipantDto> participants,
		List<WorkScopeDto> workScopes,
		Instant submittedAt,
		Instant completedAt,
		Instant createdAt,
		Instant updatedAt) {

	/** 참여자 한 명 — 이름·팀·직책(사용자 요약) + 역할 + (인수자면) 수신 상태. */
	public record ParticipantDto(
			UUID userId,
			String name,
			String team,
			String position,
			ParticipantRole role,
			String receiptStatus) {

		static ParticipantDto from(HandoverParticipant p, Map<UUID, UserSummaryResponse> users) {
			UserSummaryResponse u = users.getOrDefault(p.getUserId(), UserSummaryResponse.unknown(p.getUserId()));
			return new ParticipantDto(
					p.getUserId(),
					u.name(),
					u.team(),
					u.position(),
					p.getRole(),
					p.getReceiptStatus() == null ? null : p.getReceiptStatus().name());
		}
	}

	public record WorkScopeDto(UUID id, String title, String description) {
		static WorkScopeDto from(WorkScope w) {
			return new WorkScopeDto(w.getId(), w.getTitle(), w.getDescription());
		}
	}

	/**
	 * @param users 이 인수인계에 등장하는 userId(owner + participants) → 요약 맵. 호출부에서 배치로 채워 넘긴다.
	 */
	public static HandoverResponse of(Handover h, UUID viewerId, Map<UUID, UserSummaryResponse> users) {
		UserSummaryResponse owner = users.getOrDefault(h.getOwnerId(), UserSummaryResponse.unknown(h.getOwnerId()));
		return new HandoverResponse(
				h.getId(),
				h.getTitle(),
				h.getStatus(),
				owner,
				resolveViewerRole(h, viewerId),
				h.getParticipants().stream().map(p -> ParticipantDto.from(p, users)).toList(),
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
