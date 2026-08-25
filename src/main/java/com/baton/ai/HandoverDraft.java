package com.baton.ai;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.baton.ai.dto.HandoverDraftContent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI가 업로드 문서로부터 생성한 인수인계 초안. handover 하나당 하나만 존재하며,
 * 분석을 다시 돌리거나 보완 질문 답변을 반영할 때마다 content를 통째로 덮어쓴다.
 */
@Entity
@Table(name = "handover_drafts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HandoverDraft {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false, unique = true)
	private UUID handoverId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private HandoverDraftContent content;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** 인수자용 첫날 요약(자연어). content가 바뀔 때마다 무효화되고, 조회 시점에 없으면 다시 생성한다. */
	@Column(name = "briefing_summary", columnDefinition = "TEXT")
	private String briefingSummary;

	private HandoverDraft(UUID handoverId, HandoverDraftContent content) {
		this.handoverId = handoverId;
		this.content = content;
	}

	public static HandoverDraft create(UUID handoverId, HandoverDraftContent content) {
		return new HandoverDraft(handoverId, content);
	}

	public void replaceContent(HandoverDraftContent content) {
		this.content = content;
		this.briefingSummary = null;
	}

	public void cacheBriefingSummary(String briefingSummary) {
		this.briefingSummary = briefingSummary;
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
