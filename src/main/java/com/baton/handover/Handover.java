package com.baton.handover;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인수인계 아그리게잇 루트. 참여자(HandoverParticipant)와 업무범위(WorkScope)를 자식으로 소유한다.
 * 인계자(작성자)는 ownerId로, 인수자/관리자는 participants로 표현한다.
 *
 * 잘못된 상태를 못 만들도록 상태 전이/수정은 이 클래스의 메서드로만 하고, setter는 열지 않는다.
 */
@Entity
@Table(name = "handovers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Handover {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/** 인계자(작성자) User의 id. */
	@Column(name = "owner_id", nullable = false)
	private UUID ownerId;

	@Column(nullable = false)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private HandoverStatus status;

	@OneToMany(mappedBy = "handover", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<HandoverParticipant> participants = new ArrayList<>();

	@OneToMany(mappedBy = "handover", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<WorkScope> workScopes = new ArrayList<>();

	/** 제출 시각. 제출 전이면 null. */
	@Column(name = "submitted_at")
	private Instant submittedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private Handover(UUID ownerId, String title) {
		this.ownerId = ownerId;
		this.title = title;
		this.status = HandoverStatus.DRAFT;
	}

	/** 새 인수인계 초안 생성. 참여자/업무범위는 이후 add* 메서드로 채운다. */
	public static Handover create(UUID ownerId, String title) {
		return new Handover(ownerId, title);
	}

	// ── 기본 정보 수정(DRAFT 단계 전용) ──────────────────────

	public void rename(String title) {
		this.title = title;
	}

	public void addRecipient(UUID userId) {
		this.participants.add(new HandoverParticipant(this, userId, ParticipantRole.RECIPIENT));
	}

	public void addReviewer(UUID userId) {
		this.participants.add(new HandoverParticipant(this, userId, ParticipantRole.REVIEWER));
	}

	/** 참여자 전체 교체(PATCH). 넘어온 목록으로 갈아끼운다. */
	public void replaceRecipients(List<UUID> recipientIds) {
		this.participants.removeIf(p -> p.getRole() == ParticipantRole.RECIPIENT);
		recipientIds.forEach(this::addRecipient);
	}

	public void replaceReviewers(List<UUID> reviewerIds) {
		this.participants.removeIf(p -> p.getRole() == ParticipantRole.REVIEWER);
		reviewerIds.forEach(this::addReviewer);
	}

	public void addWorkScope(String title, String description) {
		this.workScopes.add(new WorkScope(this, title, description));
	}

	/** 업무범위 전체 교체(PATCH). */
	public void replaceWorkScopes(List<WorkScope> scopes) {
		this.workScopes.clear();
		this.workScopes.addAll(scopes);
	}

	public WorkScope newWorkScope(String title, String description) {
		return new WorkScope(this, title, description);
	}

	// ── 상태 전이 ────────────────────────────────────────────

	/** 제출 가능한 단계인가(작성/수정 중 또는 보완요청 후 재제출). */
	public boolean isSubmittable() {
		return status == HandoverStatus.DRAFT
				|| status == HandoverStatus.EDITING
				|| status == HandoverStatus.REVISION_REQUESTED;
	}

	public boolean isSubmitted() {
		return status == HandoverStatus.PENDING_REVIEW;
	}

	/** 인수자에게 전달하고 관리자 검토 대기로 전환. 제출 시각 기록. */
	public void markSubmitted() {
		this.status = HandoverStatus.PENDING_REVIEW;
		this.submittedAt = Instant.now();
	}

	/** 해당 인수자의 수신 상태를 READ로. 인수자가 아니면 아무 일도 하지 않는다(멱등). */
	public void acknowledgeBy(UUID userId) {
		this.participants.stream()
				.filter(p -> p.getRole() == ParticipantRole.RECIPIENT && p.getUserId().equals(userId))
				.forEach(HandoverParticipant::markRead);
	}

	/** 관리자 검토 대상인가(승인/보완요청 가능한 단계인가). */
	public boolean isPendingReview() {
		return status == HandoverStatus.PENDING_REVIEW;
	}

	/** 관리자가 승인. */
	public void markApproved() {
		this.status = HandoverStatus.APPROVED;
	}

	/** 관리자가 보완 요청 → 인계자가 다시 고칠 수 있는 상태로. */
	public void markRevisionRequested() {
		this.status = HandoverStatus.REVISION_REQUESTED;
	}

	// ── 권한 판정 ────────────────────────────────────────────

	public boolean isOwner(UUID userId) {
		return this.ownerId.equals(userId);
	}

	public boolean hasParticipant(UUID userId, ParticipantRole role) {
		return this.participants.stream()
				.anyMatch(p -> p.getUserId().equals(userId) && p.getRole() == role);
	}

	/** 인계자·인수자·관리자 중 하나라도 해당되면 열람 가능. */
	public boolean isViewableBy(UUID userId) {
		return isOwner(userId)
				|| hasParticipant(userId, ParticipantRole.RECIPIENT)
				|| hasParticipant(userId, ParticipantRole.REVIEWER);
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}
}
