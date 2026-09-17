package com.baton.aiusage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.ai.usage-limit 설정. 한도값 자체는 DB(ai_usage_limits)가 우선이고, 여기 defaults는 DB에 행이 없을 때의 기본값이다.
 * 한도는 인수인계서 생성·보완안 생성·채팅 요청을 합산해서 센다.
 *
 * @param enabled          한도 기능 전체 스위치(환경변수 AI_USAGE_LIMIT_ENABLED)
 * @param settingsCacheTtl DB 한도값을 서버 메모리에 캐시하는 시간. 운영자가 값을 바꾸면 이 시간 안에 반영된다.
 * @param taskLockTtl      같은 인수인계 AI 작업 중복 실행 잠금의 최대 유지 시간. 서버가 죽어 잠금을 못 풀어도 이 시간 뒤 풀린다.
 * @param retention        사용 기록 보관 기간. 하루보다 길어야 한다.
 */
@ConfigurationProperties(prefix = "app.ai.usage-limit")
public record AiUsageProperties(
		boolean enabled,
		Duration settingsCacheTtl,
		Duration taskLockTtl,
		Duration retention,
		Defaults defaults) {

	public AiUsageProperties {
		settingsCacheTtl = settingsCacheTtl == null ? Duration.ofSeconds(30) : settingsCacheTtl;
		taskLockTtl = taskLockTtl == null ? Duration.ofMinutes(5) : taskLockTtl;
		retention = retention == null || retention.compareTo(AiUsageWindow.DAY.getDuration()) <= 0
				? Duration.ofDays(2) : retention;
		defaults = defaults == null ? new Defaults(null, null) : defaults;
	}

	public record Defaults(Limit user, Limit organization) {

		public Defaults {
			user = user == null ? Limit.USER : user;
			organization = organization == null ? Limit.ORGANIZATION : organization;
		}

		public AiUsageLimits of(AiUsageScope scope) {
			Limit limit = scope == AiUsageScope.USER ? user : organization;
			return limit.enabled()
					? new AiUsageLimits(true, limit.perMinute(), limit.perHour(), limit.perDay())
					: AiUsageLimits.DISABLED;
		}
	}

	public record Limit(boolean enabled, Integer perMinute, Integer perHour, Integer perDay) {

		static final Limit USER = new Limit(true, 10, 100, 200);
		/** 조직(팀)은 회원가입 때 자유입력한 팀 이름이라 신뢰할 수 없어 기본으로 끈다. */
		static final Limit ORGANIZATION = new Limit(false, 10, 100, 200);
	}
}
