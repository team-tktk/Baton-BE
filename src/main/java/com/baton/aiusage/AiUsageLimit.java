package com.baton.aiusage;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 운영 중에 바꾸는 한도값(scope당 한 행, AI 기능 합산). 배포 없이 DB에서 UPDATE하면 캐시 주기(기본 30초) 안에 모든 서버에 반영된다.
 * 행이 없으면 application.yml의 기본값(app.ai.usage-limit.defaults)을 쓴다.
 * 기간별 값이 null이면 그 기간은 제한하지 않는다. enabled=false면 해당 scope 한도 전체를 끈다.
 */
@Entity
@Table(name = "ai_usage_limits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiUsageLimit {

	@Id
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private AiUsageScope scope;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "per_minute")
	private Integer perMinute;

	@Column(name = "per_hour")
	private Integer perHour;

	@Column(name = "per_day")
	private Integer perDay;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
