package com.baton.handover.dto;

import jakarta.validation.constraints.NotBlank;

/** 업무범위 생성/수정 입력 한 건. */
public record WorkScopeInput(
		@NotBlank String title,
		String description) {
}
