package com.baton.aiusage;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 한도 계산에 더는 쓰이지 않는 오래된 사용 기록과 만료된 잠금을 지운다. 여러 서버가 동시에 돌아도 결과는 같다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiUsageCleanup {

	private final AiUsageEventRepository eventRepository;
	private final AiTaskLockRepository lockRepository;
	private final AiUsageProperties properties;
	private final Clock clock;

	@Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
	@Transactional
	public void purge() {
		Instant now = clock.instant();
		int events = eventRepository.deleteOlderThan(now.minus(properties.retention()));
		int locks = lockRepository.deleteExpired(now);
		if (events > 0 || locks > 0) {
			log.info("[*] AI usage cleanup: events={}, expiredLocks={}", events, locks);
		}
	}
}
