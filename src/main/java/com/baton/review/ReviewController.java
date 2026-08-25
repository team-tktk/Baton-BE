package com.baton.review;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.baton.auth.AuthService;
import com.baton.handover.dto.HandoverResponse;
import com.baton.review.dto.ChecklistItemResponse;
import com.baton.review.dto.CommentRequest;
import com.baton.review.dto.CommentResponse;
import com.baton.review.dto.RequestRevisionRequest;
import com.baton.review.dto.ReviewChecklistRequest;
import com.baton.review.dto.ReviewDetailResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자(REVIEWER) 검토: 체크리스트, 코멘트, 보완요청/승인.
 * 세션 인증(이메일) → User.id 해석은 다른 컨트롤러와 같은 방식.
 */
@RestController
@RequestMapping("/api/v1/handovers/{handoverId}")
@RequiredArgsConstructor
public class ReviewController {

	private final ReviewService reviewService;
	private final AuthService authService;

	@Operation(summary = "검토 상세 조회", description = "체크리스트와 상태. 참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/review")
	public ReviewDetailResponse getReview(@PathVariable UUID handoverId, Authentication authentication) {
		return reviewService.getReview(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "검토 체크리스트 저장", description = "통째로 교체한다. 관리자만 가능.")
	@PatchMapping("/review/checklist")
	public List<ChecklistItemResponse> updateChecklist(
			@PathVariable UUID handoverId,
			@Valid @RequestBody ReviewChecklistRequest request,
			Authentication authentication) {
		return reviewService.replaceChecklist(handoverId, currentUserId(authentication), request.items());
	}

	@Operation(summary = "책임자 코멘트 목록", description = "참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/comments")
	public List<CommentResponse> listComments(@PathVariable UUID handoverId, Authentication authentication) {
		return reviewService.listComments(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "책임자 코멘트 작성", description = "관리자만 가능.")
	@PostMapping("/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public CommentResponse addComment(
			@PathVariable UUID handoverId,
			@Valid @RequestBody CommentRequest request,
			Authentication authentication) {
		return reviewService.addComment(handoverId, currentUserId(authentication), request);
	}

	@Operation(summary = "본인 코멘트 수정")
	@PatchMapping("/comments/{commentId}")
	public CommentResponse editComment(
			@PathVariable UUID handoverId,
			@PathVariable UUID commentId,
			@Valid @RequestBody CommentRequest request,
			Authentication authentication) {
		return reviewService.editComment(handoverId, commentId, currentUserId(authentication), request);
	}

	@Operation(summary = "본인 코멘트 삭제")
	@DeleteMapping("/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteComment(
			@PathVariable UUID handoverId,
			@PathVariable UUID commentId,
			Authentication authentication) {
		reviewService.deleteComment(handoverId, commentId, currentUserId(authentication));
	}

	@Operation(summary = "인계자에게 보완 요청", description = "PENDING_REVIEW → REVISION_REQUESTED. 관리자만 가능.")
	@PostMapping("/request-revision")
	public HandoverResponse requestRevision(
			@PathVariable UUID handoverId,
			@RequestBody(required = false) RequestRevisionRequest request,
			Authentication authentication) {
		String reason = request != null ? request.reason() : null;
		return reviewService.requestRevision(handoverId, currentUserId(authentication), reason);
	}

	@Operation(summary = "관리자 최종 승인", description = "PENDING_REVIEW → APPROVED. 관리자만 가능.")
	@PostMapping("/approve")
	public HandoverResponse approve(@PathVariable UUID handoverId, Authentication authentication) {
		return reviewService.approve(handoverId, currentUserId(authentication));
	}

	private UUID currentUserId(Authentication authentication) {
		return authService.getByEmail(authentication.getName()).getId();
	}
}
