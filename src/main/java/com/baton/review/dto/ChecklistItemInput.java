package com.baton.review.dto;

import jakarta.validation.constraints.NotBlank;

public record ChecklistItemInput(
		@NotBlank String label,
		boolean checked) {
}
