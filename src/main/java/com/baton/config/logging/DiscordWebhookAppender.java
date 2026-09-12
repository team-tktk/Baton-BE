package com.baton.config.logging;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

/**
 * ERROR 레벨 로그를 디스코드 웹훅으로 전송한다. 같은 로거+메시지가 짧은 시간 안에 반복되면
 * (예: 재시도 루프에서 계속 실패) 채널이 도배되는 걸 막기 위해 쿨다운을 둔다.
 */
public class DiscordWebhookAppender extends AppenderBase<ILoggingEvent> {

	private static final Duration COOLDOWN = Duration.ofSeconds(10);
	private static final int MAX_CONTENT_LENGTH = 1900;

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Map<String, Instant> lastSentAt = new ConcurrentHashMap<>();

	private String webhookUrl;

	public void setWebhookUrl(String webhookUrl) {
		this.webhookUrl = webhookUrl;
	}

	@Override
	protected void append(ILoggingEvent event) {
		if (webhookUrl == null || webhookUrl.isBlank()) {
			return;
		}

		String key = event.getLoggerName() + "|" + event.getFormattedMessage();
		Instant now = Instant.now();
		Instant last = lastSentAt.get(key);
		if (last != null && Duration.between(last, now).compareTo(COOLDOWN) < 0) {
			return;
		}
		lastSentAt.put(key, now);

		send(buildMessage(event));
	}

	private String buildMessage(ILoggingEvent event) {
		StringBuilder sb = new StringBuilder();
		sb.append("**[").append(event.getLevel()).append("] ").append(event.getLoggerName()).append("**\n");
		sb.append(event.getFormattedMessage());

		IThrowableProxy throwableProxy = event.getThrowableProxy();
		if (throwableProxy != null) {
			String stack = ThrowableProxyUtil.asString(throwableProxy);
			if (stack.length() > 1500) {
				stack = stack.substring(0, 1500) + "\n... (생략)";
			}
			sb.append("\n```\n").append(stack).append("\n```");
		}

		String content = sb.toString();
		return content.length() > MAX_CONTENT_LENGTH ? content.substring(0, MAX_CONTENT_LENGTH) + "..." : content;
	}

	private void send(String content) {
		try {
			String body = objectMapper.writeValueAsString(Map.of("content", content));

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(webhookUrl))
					.timeout(Duration.ofSeconds(5))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();

			httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
					.exceptionally(ex -> null);
		} catch (Exception e) {
			addError("디스코드 웹훅 전송 실패", e);
		}
	}
}
