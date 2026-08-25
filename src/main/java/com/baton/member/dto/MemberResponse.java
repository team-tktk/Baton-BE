package com.baton.member.dto;

import java.util.UUID;

import com.baton.auth.User;

/**
 * 구성원 검색 결과 한 건. 인수자/승인자 선택용.
 * 직책(position)을 함께 노출해 인계자가 승인자로 적합한 사람(예: 팀장)을 골라낼 수 있게 한다.
 */
public record MemberResponse(
		UUID id,
		String name,
		String team,
		String position) {

	public static MemberResponse from(User user) {
		return new MemberResponse(user.getId(), user.getName(), user.getTeam(), user.getPosition());
	}
}
