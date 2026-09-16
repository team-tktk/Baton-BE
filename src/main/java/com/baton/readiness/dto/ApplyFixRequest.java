package com.baton.readiness.dto;

import jakarta.validation.constraints.NotNull;

/** baseRevision: 사용자가 수정 전후를 비교할 때 본 문서 버전(보완안 응답의 baseRevision). */
public record ApplyFixRequest(
		@NotNull Long baseRevision) {
}
