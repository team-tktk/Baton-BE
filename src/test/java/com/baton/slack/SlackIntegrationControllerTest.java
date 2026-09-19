package com.baton.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.baton.auth.AuthService;
import com.baton.handover.HandoverAccess;

@ExtendWith(MockitoExtension.class)
class SlackIntegrationControllerTest {
	@Mock SlackIntegrationService service;
	@Mock AuthService auth;
	@Mock HandoverAccess access;
	private SlackIntegrationController controller;

	@BeforeEach
	void setUp() {
		controller = new SlackIntegrationController(service, auth, access);
	}

	@Test
	void redirectsOAuthCallbackToConfiguredFrontend() {
		SlackConnection connection = SlackConnection.create(UUID.randomUUID(), "T1", "민규 팀", "encrypted",
				"U1", "channels:read,channels:history");
		UUID connectionId = UUID.randomUUID();
		ReflectionTestUtils.setField(connection, "id", connectionId);
		ReflectionTestUtils.setField(controller, "frontendCallbackUri",
				"https://baton.example/integrations/slack/callback");
		when(service.callback("code", "state")).thenReturn(connection);

		var response = controller.callback("code", "state", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(
				"https://baton.example/integrations/slack/callback?connected=true&connectionId="
						+ connectionId + "&teamName=%EB%AF%BC%EA%B7%9C%20%ED%8C%80"));
	}

	@Test
	void returnsJsonWhenFrontendCallbackIsNotConfigured() {
		SlackConnection connection = SlackConnection.create(UUID.randomUUID(), "T1", "BATON", "encrypted",
				"U1", "channels:read");
		ReflectionTestUtils.setField(connection, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(controller, "frontendCallbackUri", "");
		when(service.callback("code", "state")).thenReturn(connection);

		var response = controller.callback("code", "state", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isInstanceOf(SlackConnectionResponse.class);
	}

	@Test
	void redirectsDeniedOAuthBackToFrontendAsFailure() {
		ReflectionTestUtils.setField(controller, "frontendCallbackUri",
				"https://baton.example/integrations/slack/callback");

		var response = controller.callback(null, "state", "access_denied");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(
				"https://baton.example/integrations/slack/callback?connected=false"));
	}
}
