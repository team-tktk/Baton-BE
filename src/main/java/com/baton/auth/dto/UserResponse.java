package com.baton.auth.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.auth.User;

/** 클라이언트에 내려주는 회원 정보. passwordHash는 절대 포함하지 않는다. */
public record UserResponse(
		UUID id,
		String email,
		String name,
		String team,
		String position,
		Instant createdAt) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getName(),
				user.getTeam(), user.getPosition(), user.getCreatedAt());
	}
}
