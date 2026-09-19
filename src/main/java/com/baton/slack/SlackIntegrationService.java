package com.baton.slack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.ExternalSourceService;
import com.baton.ai.dto.CreateSlackMessageRequest;
import com.baton.ai.dto.UpdateExternalSourceRequest;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SlackIntegrationService {
	private final SlackConnectionRepository connections;
	private final SlackSubscriptionRepository subscriptions;
	private final SlackOAuthStateRepository states;
	private final SlackImportedMessageRepository imported;
	private final SlackApiClient api;
	private final SlackTokenCipher cipher;
	private final ExternalSourceService sources;

	@Value("${app.slack.client-id:}")
	private String clientId;
	@Value("${app.slack.client-secret:}")
	private String clientSecret;
	@Value("${app.slack.redirect-uri:http://127.0.0.1:8081/api/v1/integrations/slack/oauth/callback}")
	private String redirectUri;

	@Transactional
	public String installUrl(UUID owner) {
		configured();
		SlackOAuthState state = states.save(SlackOAuthState.create(owner));
		return "https://slack.com/oauth/v2/authorize?client_id=" + clientId
				+ "&user_scope=channels:read,channels:history,groups:read,groups:history"
				+ "&redirect_uri=" + url(redirectUri) + "&state=" + state.getState();
	}

	@Transactional
	public SlackConnection callback(String code, String state) {
		configured();
		SlackOAuthState oauthState = states.findById(state)
				.filter(value -> value.getExpiresAt().isAfter(Instant.now()))
				.orElseThrow(() -> new BusinessException(ErrorCode.SLACK_OAUTH_FAILED));
		JsonNode response = api.oauth(clientId, clientSecret, code, redirectUri);
		JsonNode user = response.path("authed_user");
		if (user.path("access_token").asText().isBlank()) {
			throw new BusinessException(ErrorCode.SLACK_OAUTH_FAILED, "Slack 사용자 권한이 승인되지 않았습니다.");
		}
		String teamId = response.path("team").path("id").asText();
		SlackConnection connection = connections.findByOwnerIdAndTeamId(oauthState.getOwnerId(), teamId)
				.orElseGet(() -> SlackConnection.create(oauthState.getOwnerId(), teamId,
						response.path("team").path("name").asText(),
						cipher.encrypt(user.path("access_token").asText()), user.path("id").asText(),
						user.path("scope").asText()));
		connection.refresh(response.path("team").path("name").asText(),
				cipher.encrypt(user.path("access_token").asText()), user.path("id").asText(),
				user.path("scope").asText());
		states.delete(oauthState);
		return connections.save(connection);
	}

	@Transactional(readOnly = true)
	public List<SlackConnectionResponse> connections(UUID owner) {
		return connections.findAllByOwnerId(owner).stream().map(SlackConnectionResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<SlackApiClient.Channel> channels(UUID owner, UUID connectionId) {
		SlackConnection connection = owned(owner, connectionId);
		return api.channels(cipher.decrypt(connection.getAccessToken()));
	}

	@Transactional
	public SlackSubscriptionResponse subscribe(UUID owner, UUID handoverId, UUID connectionId,
			String channelId, String channelName) {
		owned(owner, connectionId);
		SlackSubscription subscription = subscriptions
				.findByHandoverIdAndConnectionIdAndChannelId(handoverId, connectionId, channelId)
				.orElseGet(() -> SlackSubscription.create(handoverId, connectionId, channelId, channelName));
		subscription.enable();
		subscription = subscriptions.save(subscription);
		sync(subscription);
		return response(subscription);
	}

	@Transactional(readOnly = true)
	public List<SlackSubscriptionResponse> subscriptions(UUID owner, UUID handoverId) {
		return subscriptions.findAllByHandoverId(handoverId).stream()
				.filter(subscription -> connections.findById(subscription.getConnectionId())
						.filter(connection -> connection.getOwnerId().equals(owner)).isPresent())
				.map(this::response)
				.toList();
	}

	@Transactional
	public void unsubscribe(UUID owner, UUID handoverId, UUID connectionId, UUID subscriptionId) {
		owned(owner, connectionId);
		SlackSubscription subscription = subscriptions.findById(subscriptionId)
				.filter(value -> value.getHandoverId().equals(handoverId))
				.filter(value -> value.getConnectionId().equals(connectionId))
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Slack 채널 구독을 찾을 수 없습니다."));
		subscription.disable();
		subscriptions.save(subscription);
	}

	@Scheduled(fixedDelayString = "${app.slack.sync-interval-ms:60000}", initialDelay = 30000)
	public void scheduledSync() {
		for (SlackSubscription subscription : subscriptions.findAllByEnabledTrue()) {
			try {
				sync(subscription);
			} catch (Exception ignored) {
				// 한 채널의 일시적 실패가 다른 채널 동기화를 막지 않게 한다.
			}
		}
	}

	@Transactional
	public int sync(SlackSubscription subscription) {
		SlackConnection connection = connections.findById(subscription.getConnectionId()).orElseThrow();
		String token = cipher.decrypt(connection.getAccessToken());
		SlackApiClient.History history = api.history(token, subscription.getChannelId(),
				subscription.isBackfillComplete() ? subscription.getOldestTs() : null,
				subscription.isBackfillComplete() ? null : subscription.getBackfillCursor());
		int count = 0;
		String newest = subscription.getOldestTs();
		for (SlackApiClient.Message message : history.messages()) {
			count += upsert(connection, subscription, message);
			if (message.replyCount() > 0) {
				for (SlackApiClient.Message reply : api.replies(token, subscription.getChannelId(), message.ts())) {
					if (!reply.ts().equals(message.ts())) count += upsert(connection, subscription, reply);
				}
			}
			if (newest == null || Double.parseDouble(message.ts()) > Double.parseDouble(newest)) newest = message.ts();
		}
		subscription.synced(newest, subscription.isBackfillComplete() ? null : history.nextCursor());
		subscriptions.save(subscription);
		return count;
	}

	private int upsert(SlackConnection connection, SlackSubscription subscription, SlackApiClient.Message message) {
		if (message.text() == null || message.text().isBlank()) return 0;
		String contentHash = hash(message.text());
		var previous = imported.findByHandoverIdAndConnectionIdAndChannelIdAndMessageTs(
				subscription.getHandoverId(), connection.getId(), subscription.getChannelId(), message.ts());
		String link = "https://slack.com/archives/" + subscription.getChannelId() + "/p" + message.ts().replace(".", "");
		Instant occurredAt = Instant.ofEpochMilli((long) (Double.parseDouble(message.ts()) * 1000));
		if (previous.isPresent()) {
			if (previous.get().getContentHash().equals(contentHash)) return 0;
			sources.update(subscription.getHandoverId(), previous.get().getSourceId(),
					new UpdateExternalSourceRequest("Slack #" + subscription.getChannelName(), null, link,
							subscription.getChannelName(), message.text(), occurredAt, true));
			previous.get().changed(contentHash);
			return 1;
		}
		var result = sources.createSlack(subscription.getHandoverId(),
				new CreateSlackMessageRequest(link, "Slack #" + subscription.getChannelName(),
						subscription.getChannelName(), message.text(), occurredAt, true));
		imported.save(SlackImportedMessage.create(subscription.getHandoverId(), connection.getId(),
				subscription.getChannelId(), message.ts(), result.source().getId(), contentHash));
		return 1;
	}

	@Transactional
	public void event(String teamId, String channelId, String subtype, String timestamp, String text) {
		for (SlackConnection connection : connections.findAllByTeamId(teamId)) {
			for (SlackSubscription subscription : subscriptions
					.findAllByConnectionIdAndChannelIdAndEnabledTrue(connection.getId(), channelId)) {
				var row = imported.findByHandoverIdAndConnectionIdAndChannelIdAndMessageTs(
						subscription.getHandoverId(), connection.getId(), channelId, timestamp);
				if ("message_deleted".equals(subtype)) {
					row.ifPresent(message -> {
						sources.delete(subscription.getHandoverId(), message.getSourceId());
						imported.delete(message);
					});
				} else {
					upsert(connection, subscription, new SlackApiClient.Message(timestamp, text, "", 0));
				}
			}
		}
	}

	private SlackSubscriptionResponse response(SlackSubscription subscription) {
		long count = imported.countByHandoverIdAndConnectionIdAndChannelId(subscription.getHandoverId(),
				subscription.getConnectionId(), subscription.getChannelId());
		return SlackSubscriptionResponse.from(subscription, count);
	}

	private SlackConnection owned(UUID owner, UUID id) {
		return connections.findById(id).filter(connection -> connection.getOwnerId().equals(owner))
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_FORBIDDEN));
	}

	private void configured() {
		if (clientId.isBlank() || clientSecret.isBlank()) throw new BusinessException(ErrorCode.SLACK_NOT_CONFIGURED);
	}

	private String url(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String hash(String value) {
		try {
			return Base64.getEncoder().encodeToString(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
