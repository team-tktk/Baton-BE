package com.baton.review;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 관리자 검토 체크리스트 항목 하나. PATCH /review/checklist로 통째로 교체된다(WorkScope와 같은 방식). */
@Entity
@Table(name = "review_checklist_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewChecklistItem {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Column(nullable = false)
	private String label;

	@Column(nullable = false)
	private boolean checked;

	private ReviewChecklistItem(UUID handoverId, String label, boolean checked) {
		this.handoverId = handoverId;
		this.label = label;
		this.checked = checked;
	}

	public static ReviewChecklistItem create(UUID handoverId, String label, boolean checked) {
		return new ReviewChecklistItem(handoverId, label, checked);
	}
}
