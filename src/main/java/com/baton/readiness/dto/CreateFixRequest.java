package com.baton.readiness.dto;

import java.util.List;

import com.baton.readiness.ReadinessArea;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 한 번에 보완할 부족 영역들. 영역 하나만 보내도 된다. */
public record CreateFixRequest(
		@NotEmpty @Size(max = 8) List<@NotNull ReadinessArea> areas) {
}
