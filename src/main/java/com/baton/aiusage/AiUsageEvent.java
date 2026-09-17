package com.baton.aiusage;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 요청 1건. 한도 확인을 통과한 요청만 기록한다(한도 초과로 거절된 요청은 남기지 않는다).
 * 한도는 기능 구분 없이 합산하고, feature는 어떤 기능을 썼는지 보기 위한 기록이다.
 * 서버 여러 대가 같은 DB를 보고 세기 때문에 어느 서버로 요청이 가도 같은 한도가 적용된다.
 */
@Entity
@Table(name = "ai_usage_events", indexes = {
		@Index(name = "idx_ai_usage_events_user_created", columnList = "user_id,created_at"),
		@Index(name = "idx_ai_usage_events_org_created", columnList = "organization_key,created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiUsageEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	/** 소속 팀(공백 정리). 팀이 비어 있으면 null → 조직 한도는 적용하지 않는다. */
	@Column(name = "organization_key")
	private String organizationKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private AiFeature feature;

	@Column(name = "handover_id")
	private UUID handoverId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private AiUsageEvent(UUID userId, String organizationKey, AiFeature feature, UUID handoverId, Instant createdAt) {
		this.userId = userId;
		this.organizationKey = organizationKey;
		this.feature = feature;
		this.handoverId = handoverId;
		this.createdAt = createdAt;
	}

	public static AiUsageEvent record(UUID userId, String organizationKey, AiFeature feature, UUID handoverId,
			Instant createdAt) {
		return new AiUsageEvent(userId, organizationKey, feature, handoverId, createdAt);
	}
}
