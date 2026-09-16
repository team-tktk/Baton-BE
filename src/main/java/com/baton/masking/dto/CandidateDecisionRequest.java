package com.baton.masking.dto;

import jakarta.validation.constraints.NotNull;

/** 체크박스 적용(true)/해제(false). 어느 쪽이든 "확인 완료"로 기록된다. */
public record CandidateDecisionRequest(
		@NotNull Boolean applied) {
}
