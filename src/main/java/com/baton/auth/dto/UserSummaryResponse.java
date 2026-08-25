package com.baton.auth.dto;

import java.util.UUID;

import com.baton.auth.User;

/**
 * 목록·상세 응답에 사람 정보를 함께 내려주기 위한 사용자 요약.
 * 인계자(owner)·참여자(인수자/관리자)를 userId만이 아니라 이름·팀·직책까지 담아
 * 프론트가 별도 사용자 조회(N+1) 없이 화면에 바로 표시할 수 있게 한다.
 *
 * organization(소속 조직)은 현재 별도 컬럼이 없어 team 문자열에 함께 담겨 온다(예: "모아스토어 · 운영팀").
 * 조직을 분리해 관리하게 되면 필드를 추가한다.
 */
public record UserSummaryResponse(
		UUID id,
		String name,
		String team,
		String position) {

	public static UserSummaryResponse from(User user) {
		return new UserSummaryResponse(user.getId(), user.getName(), user.getTeam(), user.getPosition());
	}

	/** 사용자가 조회되지 않을 때(삭제 등)의 안전한 기본값 — 화면이 깨지지 않게 이름만 채운다. */
	public static UserSummaryResponse unknown(UUID id) {
		return new UserSummaryResponse(id, "(알 수 없는 사용자)", null, null);
	}
}
