package com.baton.slack;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SlackConnectionResponse(
		UUID connectionId,
		String teamId,
		String teamName,
		String slackUserId,
		List<String> scopes,
		Instant connectedAt) {

	public static SlackConnectionResponse from(SlackConnection connection) {
		List<String> scopes = connection.getScopes() == null || connection.getScopes().isBlank()
				? List.of()
				: List.of(connection.getScopes().split(","));
		return new SlackConnectionResponse(connection.getId(), connection.getTeamId(), connection.getTeamName(),
				connection.getSlackUserId(), scopes, connection.getCreatedAt());
	}
}
