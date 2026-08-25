package com.baton.review.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record ReviewChecklistRequest(
		@NotNull @Valid List<ChecklistItemInput> items) {
}
