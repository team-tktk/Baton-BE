package com.baton.aiusage;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baton.aiusage.dto.AiUsageStatusResponse.UsageItem;
import com.baton.auth.User;
import com.baton.auth.UserRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * AI를 호출하기 직전에 분/시간/하루 요청 한도(인수인계서 생성·보완안 생성·채팅 합산)를 확인하고,
 * 통과하면 어떤 기능인지와 함께 1건을 기록한다.
 *
 * 서버가 여러 대여도 같은 한도가 걸리도록 카운트는 DB(ai_usage_events)에서 센다.
 * "세고 → 기록" 사이에 다른 서버의 요청이 끼어들어 한도를 넘지 않도록 사용자 단위 PostgreSQL advisory lock을
 * 트랜잭션 동안 잡는다(조직 한도가 켜져 있으면 조직 단위). REQUIRES_NEW라 AI 호출 전에 바로 커밋된다.
 * 호출하는 쪽은 DB 트랜잭션 밖에서 불러야 한다 — 안에서 부르면 요청 하나가 커넥션 두 개를 쥔다.
 */
@Service
@RequiredArgsConstructor
public class AiUsageGuard {

	private final AiUsageEventRepository eventRepository;
	private final AiUsageLimitProvider limitProvider;
	private final AiUsageProperties properties;
	private final UserRepository userRepository;
	private final EntityManager entityManager;
	private final Clock clock;

	/** 한도를 넘었으면 AiUsageLimitExceededException(429), 아니면 요청 1건을 기록한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void acquire(UUID userId, AiFeature feature, UUID handoverId) {
		if (!properties.enabled()) {
			return;
		}
		String organizationKey = organizationKeyOf(userId);
		boolean organizationLimited = organizationKey != null
				&& limitProvider.get(AiUsageScope.ORGANIZATION).enabled();
		lock(organizationLimited ? "ai-usage:org:" + organizationKey : "ai-usage:user:" + userId);

		Instant now = clock.instant();
		usages(userId, organizationKey, now).stream()
				.filter(UsageItem::blocked)
				.max(Comparator.comparing(UsageItem::retryAt))
				.ifPresent(blocked -> {
					throw new AiUsageLimitExceededException(blocked.scope(), blocked.window(), blocked.limit(),
							blocked.retryAt(), now);
				});

		eventRepository.save(AiUsageEvent.record(userId, organizationKey, feature, handoverId, now));
	}

	/** 버튼 옆 안내용 현재 사용량. 기록하지 않는다. 한도 기능이 꺼져 있으면 빈 목록. */
	@Transactional(readOnly = true)
	public List<UsageItem> currentUsage(UUID userId) {
		if (!properties.enabled()) {
			return List.of();
		}
		return usages(userId, organizationKeyOf(userId), clock.instant());
	}

	private List<UsageItem> usages(UUID userId, String organizationKey, Instant now) {
		List<UsageItem> items = new ArrayList<>();
		collect(items, AiUsageScope.USER, now, new ScopeQuery() {
			public AiUsageCounts count(Instant m, Instant h, Instant d) {
				return eventRepository.countByUser(userId, m, h, d);
			}

			public List<Instant> recent(Instant since, int offset) {
				return eventRepository.findRecentByUser(userId, since, PageRequest.of(offset, 1));
			}
		});
		if (organizationKey != null) {
			collect(items, AiUsageScope.ORGANIZATION, now, new ScopeQuery() {
				public AiUsageCounts count(Instant m, Instant h, Instant d) {
					return eventRepository.countByOrganization(organizationKey, m, h, d);
				}

				public List<Instant> recent(Instant since, int offset) {
					return eventRepository.findRecentByOrganization(organizationKey, since, PageRequest.of(offset, 1));
				}
			});
		}
		return items;
	}

	private interface ScopeQuery {
		AiUsageCounts count(Instant minuteAgo, Instant hourAgo, Instant dayAgo);

		List<Instant> recent(Instant since, int offset);
	}

	private void collect(List<UsageItem> items, AiUsageScope scope, Instant now, ScopeQuery query) {
		AiUsageLimits limits = limitProvider.get(scope);
		if (!limits.enabled()) {
			return;
		}
		AiUsageCounts counts = query.count(since(now, AiUsageWindow.MINUTE), since(now, AiUsageWindow.HOUR),
				since(now, AiUsageWindow.DAY));
		for (AiUsageWindow window : AiUsageWindow.values()) {
			Integer limit = limits.limitFor(window);
			if (limit == null) {
				continue;
			}
			long used = counts.get(window);
			Instant retryAt = used >= limit ? retryAt(query, window, limit, now) : null;
			items.add(new UsageItem(scope, window, limit, used, retryAt));
		}
	}

	/**
	 * 최신순 limit번째 요청이 기간 밖으로 밀려나면 남은 요청이 limit-1건이 되어 다시 가능하다.
	 * 그 요청 시각 + 기간이 다시 가능한 시각이다.
	 */
	private Instant retryAt(ScopeQuery query, AiUsageWindow window, int limit, Instant now) {
		return query.recent(since(now, window), limit - 1).stream()
				.findFirst()
				.map(createdAt -> createdAt.plus(window.getDuration()))
				.orElse(now);
	}

	private static Instant since(Instant now, AiUsageWindow window) {
		return now.minus(window.getDuration());
	}

	private String organizationKeyOf(UUID userId) {
		return userRepository.findById(userId)
				.map(User::getTeam)
				.map(String::strip)
				.filter(team -> !team.isEmpty())
				.orElse(null);
	}

	/** 트랜잭션이 끝나면 자동으로 풀리는 advisory lock. 해시 충돌은 서로 무관한 요청이 잠깐 줄을 서는 것뿐이라 무해하다. */
	private void lock(String key) {
		entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(:key) AS text)")
				.setParameter("key", (long) key.hashCode())
				.getSingleResult();
	}
}
