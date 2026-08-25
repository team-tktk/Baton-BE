package com.baton.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 관리자가 인수인계 문서에 남기는 코멘트 한 건. */
@Entity
@Table(name = "review_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewComment {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Column(name = "author_id", nullable = false)
	private UUID authorId;

	@Lob
	@Column(nullable = false)
	private String content;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private ReviewComment(UUID handoverId, UUID authorId, String content) {
		this.handoverId = handoverId;
		this.authorId = authorId;
		this.content = content;
	}

	public static ReviewComment create(UUID handoverId, UUID authorId, String content) {
		return new ReviewComment(handoverId, authorId, content);
	}

	public boolean isAuthor(UUID userId) {
		return this.authorId.equals(userId);
	}

	public void editContent(String content) {
		this.content = content;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}
}
