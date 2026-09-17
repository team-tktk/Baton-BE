package com.baton.aiusage;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 현재 적용할 한도값. 요청마다 DB를 읽지 않도록 settingsCacheTtl 동안 메모리에 둔다.
 * DB 조회가 실패하면 직전 값(없으면 yml 기본값)을 계속 쓴다 — 한도 설정 문제로 AI 기능 전체가 멈추지 않게.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiUsageLimitProvider {

	private final AiUsageLimitRepository limitRepository;
	private final AiUsageProperties properties;
	private final Clock clock;

	private volatile Map<AiUsageScope, AiUsageLimits> cached;
	private volatile Instant loadedAt = Instant.EPOCH;

	public AiUsageLimits get(AiUsageScope scope) {
		AiUsageLimits limits = current().get(scope);
		return limits != null ? limits : properties.defaults().of(scope);
	}

	private Map<AiUsageScope, AiUsageLimits> current() {
		Instant now = clock.instant();
		Map<AiUsageScope, AiUsageLimits> snapshot = cached;
		if (snapshot != null && loadedAt.plus(properties.settingsCacheTtl()).isAfter(now)) {
			return snapshot;
		}
		try {
			snapshot = load();
		} catch (RuntimeException e) {
			log.warn("[*] AI usage limit settings load failed, using previous values", e);
			snapshot = snapshot != null ? snapshot : Map.of();
		}
		cached = snapshot;
		loadedAt = now;
		return snapshot;
	}

	private Map<AiUsageScope, AiUsageLimits> load() {
		Map<AiUsageScope, AiUsageLimits> limits = new EnumMap<>(AiUsageScope.class);
		for (AiUsageLimit row : limitRepository.findAll()) {
			limits.put(row.getScope(), row.isEnabled()
					? new AiUsageLimits(true, row.getPerMinute(), row.getPerHour(), row.getPerDay())
					: AiUsageLimits.DISABLED);
		}
		return Map.copyOf(limits);
	}
}
