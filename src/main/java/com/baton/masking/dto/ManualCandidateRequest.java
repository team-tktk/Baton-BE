package com.baton.masking.dto;

import com.baton.masking.MaskingType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 사용자가 원문에서 직접 지정한 마스킹 구간. offset은 GET /masking 응답의 text 기준 [start, end) 구간이다.
 * type을 생략하면 CUSTOM(직접 마스킹)으로 저장한다.
 */
public record ManualCandidateRequest(
		@NotNull @PositiveOrZero Integer startOffset,
		@NotNull @Positive Integer endOffset,
		MaskingType type) {

	public MaskingType typeOrDefault() {
		return type != null ? type : MaskingType.CUSTOM;
	}
}
