package com.baton.slack;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.baton.auth.AuthService;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.HandoverAccess;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/integrations/slack")
@RequiredArgsConstructor
public class SlackIntegrationController {
	private final SlackIntegrationService service;
	private final AuthService auth;
	private final HandoverAccess access;

	@Value("${app.slack.frontend-callback-uri:}")
	private String frontendCallbackUri;

	@GetMapping("/install-url")
	public Map<String, String> install(Authentication authentication) {
		return Map.of("url", service.installUrl(currentUserId(authentication)));
	}

	@GetMapping("/oauth/callback")
	public ResponseEntity<?> callback(@RequestParam(required = false) String code,
			@RequestParam(required = false) String state, @RequestParam(required = false) String error) {
		if (error != null || code == null || state == null) {
			if (frontendCallbackUri != null && !frontendCallbackUri.isBlank()) {
				return ResponseEntity.status(HttpStatus.FOUND).location(callbackLocation(false, null)).build();
			}
			throw new BusinessException(ErrorCode.SLACK_OAUTH_FAILED,
					"Slack 연결이 취소되었거나 승인되지 않았습니다.");
		}
		SlackConnectionResponse connection = SlackConnectionResponse.from(service.callback(code, state));
		if (frontendCallbackUri == null || frontendCallbackUri.isBlank()) {
			return ResponseEntity.ok(connection);
		}
		return ResponseEntity.status(HttpStatus.FOUND).location(callbackLocation(true, connection)).build();
	}

	@GetMapping("/connections")
	public List<SlackConnectionResponse> connections(Authentication authentication) {
		return service.connections(currentUserId(authentication));
	}

	@GetMapping("/{connectionId}/channels")
	public List<SlackApiClient.Channel> channels(@PathVariable UUID connectionId,
			Authentication authentication) {
		return service.channels(currentUserId(authentication), connectionId);
	}

	@GetMapping("/subscriptions")
	public List<SlackSubscriptionResponse> subscriptions(@RequestParam UUID handoverId,
			Authentication authentication) {
		UUID ownerId = access.requireOwner(handoverId, authentication);
		return service.subscriptions(ownerId, handoverId);
	}

	@PostMapping("/{connectionId}/subscriptions")
	public SlackSubscriptionResponse subscribe(@PathVariable UUID connectionId,
			@Valid @RequestBody SubscribeRequest request, Authentication authentication) {
		UUID ownerId = access.requireOwner(request.handoverId(), authentication);
		return service.subscribe(ownerId, request.handoverId(), connectionId, request.channelId(),
				request.channelName());
	}

	@DeleteMapping("/{connectionId}/subscriptions/{subscriptionId}")
	public ResponseEntity<Void> unsubscribe(@PathVariable UUID connectionId,
			@PathVariable UUID subscriptionId, @RequestParam UUID handoverId,
			Authentication authentication) {
		UUID ownerId = access.requireOwner(handoverId, authentication);
		service.unsubscribe(ownerId, handoverId, connectionId, subscriptionId);
		return ResponseEntity.noContent().build();
	}

	private UUID currentUserId(Authentication authentication) {
		return auth.getByEmail(authentication.getName()).getId();
	}

	private URI callbackLocation(boolean connected, SlackConnectionResponse connection) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(frontendCallbackUri)
				.queryParam("connected", connected);
		if (connection != null) {
			builder.queryParam("connectionId", connection.connectionId())
					.queryParam("teamName", connection.teamName());
		}
		return builder.build().encode().toUri();
	}

	public record SubscribeRequest(@NotNull UUID handoverId, @NotBlank String channelId,
			@NotBlank String channelName) {}
}
