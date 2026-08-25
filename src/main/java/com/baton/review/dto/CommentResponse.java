package com.baton.review.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.review.ReviewComment;

public record CommentResponse(
		UUID id,
		UUID authorId,
		String authorName,
		String content,
		Instant createdAt,
		Instant updatedAt) {

	public static CommentResponse of(ReviewComment comment, String authorName) {
		return new CommentResponse(
				comment.getId(),
				comment.getAuthorId(),
				authorName,
				comment.getContent(),
				comment.getCreatedAt(),
				comment.getUpdatedAt());
	}
}
