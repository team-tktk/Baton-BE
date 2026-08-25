package com.baton.handover.dto;

import java.util.List;
import java.util.UUID;

/**
 * 인수인계 기본 정보 수정 요청(PATCH). null인 필드는 "변경 안 함"으로 해석한다.
 * 리스트를 보내면 해당 종류(인수자/승인자/업무범위)를 통째로 교체한다.
 * (승인자/reviewer는 인계자가 화면에서 직접 검색해 지정한다.)
 */
public record UpdateHandoverRequest(
		String title,
		List<UUID> recipientIds,
		List<UUID> reviewerIds,
		List<WorkScopeInput> workScopes) {
}
