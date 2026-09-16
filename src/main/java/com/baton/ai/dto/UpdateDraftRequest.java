package com.baton.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 초안 수동 수정. 필드 단위 patch 대신 content 전체를 통째로 교체한다.
 * baseRevision을 보내면 현재 문서 버전과 다를 때 저장을 거절한다(생략하면 기존처럼 마지막 저장이 이긴다).
 */
public record UpdateDraftRequest(
		@NotNull @Valid HandoverDraftContent content,
		Long baseRevision) {
}
