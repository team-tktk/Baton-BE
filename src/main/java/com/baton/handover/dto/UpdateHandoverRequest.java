package com.baton.handover.dto;

import java.util.List;
import java.util.UUID;

/**
 * 인수인계 기본 정보 수정 요청(PATCH). null인 필드는 "변경 안 함"으로 해석한다.
 * 리스트를 보내면 해당 종류(인수자/업무범위)를 통째로 교체한다.
 * (관리자/reviewer는 인계자가 지정하지 않는다 — 제출 시 조직/팀 기준으로 결정.)
 */
public record UpdateHandoverRequest(
		String title,
		List<UUID> recipientIds,
		List<WorkScopeInput> workScopes) {
}
