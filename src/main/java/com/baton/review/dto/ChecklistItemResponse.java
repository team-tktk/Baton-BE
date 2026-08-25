package com.baton.review.dto;

import java.util.UUID;

import com.baton.review.ReviewChecklistItem;

public record ChecklistItemResponse(
		UUID id,
		String label,
		boolean checked) {

	public static ChecklistItemResponse from(ReviewChecklistItem item) {
		return new ChecklistItemResponse(item.getId(), item.getLabel(), item.isChecked());
	}
}
