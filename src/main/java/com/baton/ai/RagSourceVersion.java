package com.baton.ai;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 검색에서는 제외하지만 감사·복구를 위해 보관하는 RAG 자료의 이전 버전. */
@Entity
@Table(name = "rag_source_versions", uniqueConstraints =
		@UniqueConstraint(name = "uk_rag_source_version_content", columnNames = { "source_id", "content_hash" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RagSourceVersion {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	/** 현재 자료와 과거 버전을 묶는 논리 ID. 원본이 삭제돼도 유지한다. */
	@Column(name = "source_id", nullable = false)
	private UUID sourceId;

	@Column(name = "source_type", nullable = false, length = 30)
	private String sourceType;

	@Column(nullable = false)
	private String title;

	@Column(length = 500)
	private String locator;

	@Column(columnDefinition = "TEXT", nullable = false)
	private String content;

	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	@Column(name = "source_revision")
	private Long sourceRevision;

	@Column(name = "source_updated_at")
	private Instant sourceUpdatedAt;

	@Column(name = "archived_at", nullable = false, updatable = false)
	private Instant archivedAt;

	private RagSourceVersion(UUID handoverId, UUID sourceId, String sourceType, String title, String locator,
			String content, String contentHash, Long sourceRevision, Instant sourceUpdatedAt) {
		this.handoverId = handoverId;
		this.sourceId = sourceId;
		this.sourceType = sourceType;
		this.title = title;
		this.locator = locator;
		this.content = content;
		this.contentHash = contentHash;
		this.sourceRevision = sourceRevision;
		this.sourceUpdatedAt = sourceUpdatedAt;
	}

	static RagSourceVersion create(UUID handoverId, UUID sourceId, String sourceType, String title, String locator,
			String content, String contentHash, Long sourceRevision, Instant sourceUpdatedAt) {
		return new RagSourceVersion(handoverId, sourceId, sourceType, title, locator, content, contentHash,
				sourceRevision, sourceUpdatedAt);
	}

	@PrePersist
	void onCreate() {
		archivedAt = Instant.now();
	}
}
