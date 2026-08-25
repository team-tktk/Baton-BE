package com.baton.handover;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인수인계에 참여하는 인수자(RECIPIENT) 또는 관리자(REVIEWER) 한 명.
 * 사용자는 UUID(userId)로만 참조한다 — auth 도메인과 느슨하게 결합하기 위해 @ManyToOne User 대신 값을 들고 있다.
 * (SourceDocument가 handoverId를 raw UUID로 들고 있는 것과 같은 방침.)
 */
@Entity
@Table(name = "handover_participants",
		uniqueConstraints = @UniqueConstraint(columnNames = {"handover_id", "user_id", "role"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HandoverParticipant {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "handover_id", nullable = false)
	private Handover handover;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ParticipantRole role;

	/** 인수자의 수신 상태. 관리자(REVIEWER)에게는 의미가 없어 null. */
	@Enumerated(EnumType.STRING)
	@Column(name = "receipt_status", length = 10)
	private ReceiptStatus receiptStatus;

	HandoverParticipant(Handover handover, UUID userId, ParticipantRole role) {
		this.handover = handover;
		this.userId = userId;
		this.role = role;
		// 인수자는 처음엔 미열람 상태에서 시작, 관리자는 수신 개념이 없음
		this.receiptStatus = (role == ParticipantRole.RECIPIENT) ? ReceiptStatus.UNREAD : null;
	}

	/** 인수자가 문서를 처음 열었을 때(acknowledge) 호출. */
	public void markRead() {
		if (role == ParticipantRole.RECIPIENT) {
			this.receiptStatus = ReceiptStatus.READ;
		}
	}
}
