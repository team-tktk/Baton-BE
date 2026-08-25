package com.baton.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 초안 수동 수정. 필드 단위 patch 대신 content 전체를 통째로 교체한다(버전 동시성 제어는 생략). */
public record UpdateDraftRequest(
		@NotNull @Valid HandoverDraftContent content) {
}
