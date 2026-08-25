package com.baton.review.dto;

import java.util.List;
import java.util.UUID;

public record ReviewDetailResponse(
		UUID handoverId,
		String status,
		List<ChecklistItemResponse> checklist) {
}
