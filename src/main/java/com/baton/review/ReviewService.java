package com.baton.review;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.auth.User;
import com.baton.auth.UserRepository;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverPermission;
import com.baton.handover.HandoverRepository;
import com.baton.handover.dto.HandoverResponse;
import com.baton.review.dto.ChecklistItemInput;
import com.baton.review.dto.ChecklistItemResponse;
import com.baton.review.dto.CommentRequest;
import com.baton.review.dto.CommentResponse;
import com.baton.review.dto.ReviewDetailResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자(REVIEWER) 검토: 체크리스트, 코멘트, 보완요청/승인.
 * 권한은 URL이 아니라 HandoverPermission으로 실제 참여자 관계를 확인해서 판단한다.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

	private final HandoverRepository handoverRepository;
	private final HandoverPermission permission;
	private final ReviewChecklistItemRepository checklistItemRepository;
	private final ReviewCommentRepository commentRepository;
	private final UserRepository userRepository;

	@Transactional(readOnly = true)
	public ReviewDetailResponse getReview(UUID handoverId, UUID viewerId) {
		Handover handover = load(handoverId);
		permission.requireViewer(handover, viewerId);

		List<ChecklistItemResponse> checklist = checklistItemRepository.findAllByHandoverId(handoverId).stream()
				.map(ChecklistItemResponse::from)
				.toList();

		return new ReviewDetailResponse(handoverId, handover.getStatus().name(), checklist);
	}

	/** 체크리스트를 통째로 교체한다(WorkScope와 같은 방식). */
	@Transactional
	public List<ChecklistItemResponse> replaceChecklist(UUID handoverId, UUID reviewerId, List<ChecklistItemInput> items) {
		Handover handover = load(handoverId);
		permission.requireReviewer(handover, reviewerId);

		checklistItemRepository.deleteAllByHandoverId(handoverId);
		List<ReviewChecklistItem> saved = checklistItemRepository.saveAll(
				items.stream()
						.map(item -> ReviewChecklistItem.create(handoverId, item.label(), item.checked()))
						.toList());

		return saved.stream().map(ChecklistItemResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<CommentResponse> listComments(UUID handoverId, UUID viewerId) {
		Handover handover = load(handoverId);
		permission.requireViewer(handover, viewerId);

		return commentRepository.findAllByHandoverIdOrderByCreatedAtAsc(handoverId).stream()
				.map(comment -> CommentResponse.of(comment, authorNameOf(comment.getAuthorId())))
				.toList();
	}

	@Transactional
	public CommentResponse addComment(UUID handoverId, UUID reviewerId, CommentRequest request) {
		Handover handover = load(handoverId);
		permission.requireReviewer(handover, reviewerId);

		ReviewComment comment = commentRepository.save(ReviewComment.create(handoverId, reviewerId, request.content()));
		return CommentResponse.of(comment, authorNameOf(reviewerId));
	}

	@Transactional
	public CommentResponse editComment(UUID handoverId, UUID commentId, UUID userId, CommentRequest request) {
		ReviewComment comment = loadComment(handoverId, commentId);
		if (!comment.isAuthor(userId)) {
			throw new BusinessException(ErrorCode.HANDOVER_FORBIDDEN, "본인 코멘트만 수정할 수 있습니다.");
		}
		comment.editContent(request.content());
		return CommentResponse.of(comment, authorNameOf(userId));
	}

	@Transactional
	public void deleteComment(UUID handoverId, UUID commentId, UUID userId) {
		ReviewComment comment = loadComment(handoverId, commentId);
		if (!comment.isAuthor(userId)) {
			throw new BusinessException(ErrorCode.HANDOVER_FORBIDDEN, "본인 코멘트만 삭제할 수 있습니다.");
		}
		commentRepository.delete(comment);
	}

	/** 관리자가 보완을 요청한다(→ REVISION_REQUESTED). 이유를 남기면 코멘트로도 기록한다. */
	@Transactional
	public HandoverResponse requestRevision(UUID handoverId, UUID reviewerId, String reason) {
		Handover handover = load(handoverId);
		permission.requireReviewer(handover, reviewerId);
		requirePendingReview(handover);

		handover.markRevisionRequested();
		if (reason != null && !reason.isBlank()) {
			commentRepository.save(ReviewComment.create(handoverId, reviewerId, reason));
		}
		return HandoverResponse.of(handover, reviewerId);
	}

	/** 관리자가 최종 승인한다(→ APPROVED). */
	@Transactional
	public HandoverResponse approve(UUID handoverId, UUID reviewerId) {
		Handover handover = load(handoverId);
		permission.requireReviewer(handover, reviewerId);
		requirePendingReview(handover);

		handover.markApproved();
		return HandoverResponse.of(handover, reviewerId);
	}

	private void requirePendingReview(Handover handover) {
		if (!handover.isPendingReview()) {
			throw new BusinessException(ErrorCode.HANDOVER_INVALID_STATE,
					"검토 대기 상태가 아닙니다: " + handover.getStatus());
		}
	}

	private Handover load(UUID handoverId) {
		return handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
	}

	private ReviewComment loadComment(UUID handoverId, UUID commentId) {
		ReviewComment comment = commentRepository.findById(commentId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND, "존재하지 않는 코멘트입니다."));
		if (!comment.getHandoverId().equals(handoverId)) {
			throw new BusinessException(ErrorCode.HANDOVER_NOT_FOUND, "존재하지 않는 코멘트입니다.");
		}
		return comment;
	}

	private String authorNameOf(UUID userId) {
		return userRepository.findById(userId).map(User::getName).orElse("알 수 없음");
	}
}
