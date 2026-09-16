package com.baton.readiness;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인수인계 문서 한 버전에 대한 준비도 평가 결과. 평가할 때마다 새 행을 쌓는다(이력).
 * contentHash = 문서 내용 + 업로드 자료 + 평가 기준 버전의 해시. 해시가 같은 결과가 있으면 다시 평가하지 않고 재사용한다.
 */
@Entity
@Table(name = "readiness_evaluations",
		indexes = @Index(name = "idx_readiness_evaluations_handover", columnList = "handover_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadinessEvaluation {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Column(name = "rubric_version", nullable = false, length = 20)
	private String rubricVersion;

	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	/** 평가한 문서 버전(HandoverDraft.revision). */
	@Column(name = "draft_revision", nullable = false)
	private long draftRevision;

	@Column(nullable = false)
	private int score;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<ReadinessItem> items;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private ReadinessEvaluation(UUID handoverId, ReadinessRubric rubric, String contentHash, long draftRevision,
			List<ReadinessItem> items) {
		this.handoverId = handoverId;
		this.rubricVersion = rubric.version();
		this.contentHash = contentHash;
		this.draftRevision = draftRevision;
		this.items = items;
		this.score = rubric.score(statuses(items));
	}

	public static ReadinessEvaluation create(UUID handoverId, ReadinessRubric rubric, String contentHash,
			long draftRevision, List<ReadinessItem> items) {
		return new ReadinessEvaluation(handoverId, rubric, contentHash, draftRevision, items);
	}

	public Map<ReadinessArea, ReadinessStatus> statuses() {
		return statuses(items);
	}

	public Optional<ReadinessItem> item(ReadinessArea area) {
		return items.stream().filter(item -> item.area() == area).findFirst();
	}

	private static Map<ReadinessArea, ReadinessStatus> statuses(List<ReadinessItem> items) {
		Map<ReadinessArea, ReadinessStatus> statuses = new EnumMap<>(ReadinessArea.class);
		items.forEach(item -> statuses.put(item.area(), item.status()));
		return statuses;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}
}
