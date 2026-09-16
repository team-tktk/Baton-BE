package com.baton.masking;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 업로드 파일(SourceDocument) 하나에서 찾았거나 사용자가 지정한 마스킹 구간 한 건.
 *
 * 위치(startOffset, endOffset)는 추출된 원문 텍스트(SourceDocument.extractedText) 기준 [start, end) 구간이다.
 * 원문 값 자체는 저장하지 않는다. 검수 중에는 원문 텍스트에서 잘라 보여주고, 목록에는 preview만 쓴다.
 *
 * 체크박스 = applied, "확인 필요" = needsReview && !reviewed.
 * 파일의 확인 필요 항목이 하나라도 남아 있으면 검수를 확정할 수 없다.
 */
@Entity
@Table(name = "masking_candidates",
		indexes = @Index(name = "idx_masking_candidates_source_document", columnList = "source_document_id, start_offset"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaskingCandidate {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "source_document_id", nullable = false)
	private UUID sourceDocumentId;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MaskingType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MaskingOrigin origin;

	@Column(name = "start_offset", nullable = false)
	private int startOffset;

	@Column(name = "end_offset", nullable = false)
	private int endOffset;

	/** 규칙 기반 신뢰도(0~1). 직접 추가한 후보는 1. */
	@Column(nullable = false)
	private double confidence;

	/** 확정 시 이 구간을 가릴지(체크박스). */
	@Column(nullable = false)
	private boolean applied;

	/** 자동 판단이 애매해서 사용자 확인이 필요한 후보인지. */
	@Column(name = "needs_review", nullable = false)
	private boolean needsReview;

	/** 사용자가 이 후보를 한 번이라도 확인(적용/해제)했는지. */
	@Column(nullable = false)
	private boolean reviewed;

	/** 목록 표시용 부분 가림 문자열(예: min***@example.com). */
	@Column(nullable = false, length = 120)
	private String preview;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private MaskingCandidate(UUID sourceDocumentId, UUID handoverId, MaskingType type, MaskingOrigin origin,
			int startOffset, int endOffset, double confidence, boolean needsReview, String original) {
		this.sourceDocumentId = sourceDocumentId;
		this.handoverId = handoverId;
		this.type = type;
		this.origin = origin;
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.confidence = confidence;
		this.applied = true;
		this.needsReview = needsReview;
		this.reviewed = !needsReview;
		this.preview = MaskingPreview.of(type, original);
	}

	/** 탐지기가 찾은 후보. 기본은 가리기(체크)이고, 애매한 후보만 사용자 확인을 요구한다. */
	public static MaskingCandidate detected(UUID sourceDocumentId, UUID handoverId,
			MaskingDetector.DetectedCandidate detected, String text) {
		String original = text.substring(detected.startOffset(), detected.endOffset());
		return new MaskingCandidate(sourceDocumentId, handoverId, detected.type(), MaskingOrigin.DETECTED,
				detected.startOffset(), detected.endOffset(), detected.confidence(), detected.needsReview(), original);
	}

	/** 사용자가 직접 지정한 구간. 사용자가 고른 것이므로 이미 확인된 상태로 만든다. */
	public static MaskingCandidate manual(UUID sourceDocumentId, UUID handoverId, MaskingType type,
			int startOffset, int endOffset, String text) {
		String original = text.substring(startOffset, endOffset);
		return new MaskingCandidate(sourceDocumentId, handoverId, type, MaskingOrigin.MANUAL,
				startOffset, endOffset, 1.0, false, original);
	}

	/** 체크/해제. 어느 쪽이든 사용자가 확인한 것으로 기록한다. */
	public void decide(boolean applied) {
		this.applied = applied;
		this.reviewed = true;
	}

	public boolean isPendingReview() {
		return needsReview && !reviewed;
	}

	public boolean overlaps(int start, int end) {
		return start < endOffset && startOffset < end;
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
