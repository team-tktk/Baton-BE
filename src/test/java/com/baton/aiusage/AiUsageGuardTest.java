package com.baton.aiusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import com.baton.aiusage.dto.AiUsageStatusResponse.UsageItem;
import com.baton.auth.User;
import com.baton.auth.UserRepository;
import com.baton.common.ErrorCode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@ExtendWith(MockitoExtension.class)
class AiUsageGuardTest {

	private static final Instant NOW = Instant.parse("2026-09-17T05:30:00Z");
	private static final AiUsageLimits DEFAULT = new AiUsageLimits(true, 10, 100, 200);

	@Mock
	private AiUsageEventRepository eventRepository;
	@Mock
	private AiUsageLimitProvider limitProvider;
	@Mock
	private UserRepository userRepository;
	@Mock
	private EntityManager entityManager;
	@Mock
	private Query lockQuery;

	private final UUID userId = UUID.randomUUID();
	private final UUID handoverId = UUID.randomUUID();

	private AiUsageGuard guard(boolean enabled) {
		AiUsageProperties properties = new AiUsageProperties(enabled, null, null, null, null);
		return new AiUsageGuard(eventRepository, limitProvider, properties, userRepository, entityManager,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@BeforeEach
	void setUp() {
		lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
		lenient().when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
		lenient().when(limitProvider.get(AiUsageScope.USER)).thenReturn(DEFAULT);
		lenient().when(limitProvider.get(AiUsageScope.ORGANIZATION)).thenReturn(AiUsageLimits.DISABLED);
	}

	private void userInTeam(String team) {
		when(userRepository.findById(userId))
				.thenReturn(Optional.of(User.create("a@b.com", "hash", "이름", team, null)));
	}

	@Test
	void recordsRequestWhenUnderLimits() {
		userInTeam("  운영팀 ");
		when(eventRepository.countByUser(eq(userId), any(), any(), any()))
				.thenReturn(new AiUsageCounts(9L, 99L, 199L));

		guard(true).acquire(userId, AiFeature.CHAT, handoverId);

		ArgumentCaptor<AiUsageEvent> saved = ArgumentCaptor.forClass(AiUsageEvent.class);
		verify(eventRepository).save(saved.capture());
		assertThat(saved.getValue().getOrganizationKey()).isEqualTo("운영팀");
		assertThat(saved.getValue().getFeature()).isEqualTo(AiFeature.CHAT);
		assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
		// 조직 한도가 꺼져 있으면 조직 사용량은 세지 않고, 사용자 단위로 줄을 세운다
		verify(eventRepository, never()).countByOrganization(anyString(), any(), any(), any());
		verify(lockQuery).setParameter("key", (long) ("ai-usage:user:" + userId).hashCode());
	}

	@Test
	void rejectsWithRetryTimeWhenMinuteLimitReached() {
		userInTeam("");
		when(eventRepository.countByUser(eq(userId), any(), any(), any()))
				.thenReturn(new AiUsageCounts(10L, 10L, 10L));
		// 최신순 10번째 요청이 25초 전 → 35초 뒤 1분 구간에서 빠진다
		when(eventRepository.findRecentByUser(userId, NOW.minus(Duration.ofMinutes(1)),
				PageRequest.of(9, 1))).thenReturn(List.of(NOW.minusSeconds(25)));

		assertThatThrownBy(() -> guard(true).acquire(userId, AiFeature.DRAFT_GENERATION, handoverId))
				.isInstanceOfSatisfying(AiUsageLimitExceededException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_USAGE_LIMIT_EXCEEDED);
					assertThat(e.getScope()).isEqualTo(AiUsageScope.USER);
					assertThat(e.getWindow()).isEqualTo(AiUsageWindow.MINUTE);
					assertThat(e.getLimit()).isEqualTo(10);
					assertThat(e.getRetryAt()).isEqualTo(NOW.plusSeconds(35));
					assertThat(e.getRetryAfterSeconds()).isEqualTo(35);
					assertThat(e.getMessage()).startsWith("AI 요청이 너무 많아요(사용자 1분 10회)");
				});

		verify(eventRepository, never()).save(any());
	}

	@Test
	void countsAllFeaturesTogether() {
		userInTeam(null);
		// 채팅으로 1분 한도를 채웠으면 보완안 생성도 막힌다
		when(eventRepository.countByUser(eq(userId), any(), any(), any())).thenReturn(new AiUsageCounts(10L, 10L, 10L));
		when(eventRepository.findRecentByUser(eq(userId), any(), any())).thenReturn(List.of(NOW.minusSeconds(1)));

		assertThatThrownBy(() -> guard(true).acquire(userId, AiFeature.READINESS_FIX, handoverId))
				.isInstanceOf(AiUsageLimitExceededException.class);

		verify(eventRepository, never()).save(any());
	}

	@Test
	void usesLatestRetryTimeWhenOrganizationLimitsAreReached() {
		userInTeam("운영팀");
		when(limitProvider.get(AiUsageScope.ORGANIZATION)).thenReturn(DEFAULT);
		when(eventRepository.countByUser(eq(userId), any(), any(), any()))
				.thenReturn(new AiUsageCounts(1L, 1L, 1L));
		when(eventRepository.countByOrganization(eq("운영팀"), any(), any(), any()))
				.thenReturn(new AiUsageCounts(10L, 100L, 150L));
		when(eventRepository.findRecentByOrganization("운영팀", NOW.minus(Duration.ofMinutes(1)),
				PageRequest.of(9, 1))).thenReturn(List.of(NOW.minusSeconds(50)));
		when(eventRepository.findRecentByOrganization("운영팀", NOW.minus(Duration.ofHours(1)),
				PageRequest.of(99, 1))).thenReturn(List.of(NOW.minus(Duration.ofMinutes(40))));

		assertThatThrownBy(() -> guard(true).acquire(userId, AiFeature.CHAT, handoverId))
				.isInstanceOfSatisfying(AiUsageLimitExceededException.class, e -> {
					assertThat(e.getScope()).isEqualTo(AiUsageScope.ORGANIZATION);
					assertThat(e.getWindow()).isEqualTo(AiUsageWindow.HOUR);
					assertThat(e.getRetryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(20)));
				});
		// 조직 한도가 켜져 있으면 같은 조직 요청끼리 줄을 세운다
		verify(lockQuery).setParameter("key", (long) "ai-usage:org:운영팀".hashCode());
	}

	@Test
	void skipsUnlimitedWindow() {
		userInTeam(null);
		when(limitProvider.get(AiUsageScope.USER)).thenReturn(new AiUsageLimits(true, null, null, 200));
		when(eventRepository.countByUser(eq(userId), any(), any(), any()))
				.thenReturn(new AiUsageCounts(50L, 150L, 199L));

		guard(true).acquire(userId, AiFeature.CHAT, handoverId);

		verify(eventRepository).save(any());
	}

	@Test
	void doesNothingWhenFeatureSwitchIsOff() {
		guard(false).acquire(userId, AiFeature.CHAT, handoverId);

		verifyNoInteractions(eventRepository, userRepository, entityManager);
	}

	@Test
	void reportsRemainingUsageWithoutRecording() {
		userInTeam(null);
		when(eventRepository.countByUser(eq(userId), any(), any(), any())).thenReturn(new AiUsageCounts(3L, 30L, 200L));
		when(eventRepository.findRecentByUser(eq(userId), eq(NOW.minus(Duration.ofDays(1))), any()))
				.thenReturn(List.of(NOW.minus(Duration.ofHours(23))));

		List<UsageItem> usages = guard(true).currentUsage(userId);

		assertThat(usages).extracting(UsageItem::window, UsageItem::used, UsageItem::retryAt).containsExactly(
				tuple(AiUsageWindow.MINUTE, 3L, null),
				tuple(AiUsageWindow.HOUR, 30L, null),
				tuple(AiUsageWindow.DAY, 200L, NOW.plus(Duration.ofHours(1))));
		verify(eventRepository, never()).save(any());
		verifyNoInteractions(entityManager);
	}
}
