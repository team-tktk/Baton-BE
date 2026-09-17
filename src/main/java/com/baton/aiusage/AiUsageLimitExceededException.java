package com.baton.aiusage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

import lombok.Getter;

/** AI 요청 한도 초과(429). 어떤 한도에 걸렸는지와 다시 가능한 시각을 응답에 함께 싣는다. */
@Getter
public class AiUsageLimitExceededException extends BusinessException {

	private static final DateTimeFormatter TIME_FMT =
			DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"));

	private final transient AiUsageScope scope;
	private final transient AiUsageWindow window;
	private final int limit;
	private final transient Instant retryAt;
	private final long retryAfterSeconds;

	public AiUsageLimitExceededException(AiUsageScope scope, AiUsageWindow window, int limit, Instant retryAt,
			Instant now) {
		super(ErrorCode.AI_USAGE_LIMIT_EXCEEDED, "AI 요청이 너무 많아요(%s %s %d회). %s부터 다시 사용할 수 있어요."
				.formatted(scope.getLabel(), window.getLabel(), limit, TIME_FMT.format(retryAt)));
		this.scope = scope;
		this.window = window;
		this.limit = limit;
		this.retryAt = retryAt;
		this.retryAfterSeconds = retryAfterSeconds(now, retryAt);
	}

	/** 초 단위 올림. 이미 지난 시각이어도 최소 1초. */
	public static long retryAfterSeconds(Instant now, Instant retryAt) {
		long millis = Duration.between(now, retryAt).toMillis();
		return Math.max(1, (millis + 999) / 1000);
	}
}
