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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자(REVIEWER) 검토: 체크리스트, 코멘트, 보완요청/승인.
 * 세션 인증(이메일) → User.id 해석은 다른 컨트롤러와 같은 방식.
 */
@Tag(name = "06. 관리자 검토", description = "관리자(reviewer)의 검토 상세·체크리스트·코멘트·보완요청·최종 승인.")
@RestController
@RequestMapping("/api/v1/handovers/{handoverId}")
@RequiredArgsConstructor
public class ReviewController {

	private final ReviewService reviewService;
	private final AuthService authService;

	@Operation(summary = "검토 상세 조회",
			description = """
					검토 화면 한 번에 필요한 문서 초안(`document`)·첨부(`attachments`)·체크리스트(`checklist`)·코멘트(`comments`)와 상태를 함께 반환한다.
					초안이 아직 없으면 `document`는 null. 참여자(인계자/인수자/관리자) 모두 조회 가능.
					""")
	@GetMapping("/review")
	public ReviewDetailResponse getReview(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			Authentication authentication) {
		return reviewService.getReview(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "검토 체크리스트 저장",
			description = """
					체크리스트를 통째로 교체한다(부분 수정 아님, 보낸 배열이 전체가 됨). 관리자만 가능.
					승인하려면 이 체크리스트가 비어 있지 않고 모든 항목이 체크돼 있어야 한다(→ `POST /approve` 참고).
					""")
	@PatchMapping("/review/checklist")
	public List<ChecklistItemResponse> updateChecklist(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			@Valid @RequestBody ReviewChecklistRequest request,
			Authentication authentication) {
		return reviewService.replaceChecklist(handoverId, currentUserId(authentication), request.items());
	}

	@Operation(summary = "코멘트 목록 조회",
			description = "책임자 코멘트를 작성 시각 오름차순으로 반환한다. 참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/comments")
	public List<CommentResponse> listComments(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			Authentication authentication) {
		return reviewService.listComments(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "코멘트 작성",
			description = "관리자만 작성 가능. 성공: `201 Created` + 생성된 코멘트.")
	@PostMapping("/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public CommentResponse addComment(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			@Valid @RequestBody CommentRequest request,
			Authentication authentication) {
		return reviewService.addComment(handoverId, currentUserId(authentication), request);
	}

	@Operation(summary = "본인 코멘트 수정",
			description = "작성자 본인만 수정 가능. 타인 코멘트: `403`(code=`HANDOVER_FORBIDDEN`).")
	@PatchMapping("/comments/{commentId}")
	public CommentResponse editComment(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			@Parameter(description = "코멘트 id") @PathVariable UUID commentId,
			@Valid @RequestBody CommentRequest request,
			Authentication authentication) {
		return reviewService.editComment(handoverId, commentId, currentUserId(authentication), request);
	}

	@Operation(summary = "본인 코멘트 삭제",
			description = "작성자 본인만 삭제 가능. 성공: `204 No Content`. 타인 코멘트: `403`(code=`HANDOVER_FORBIDDEN`).")
	@DeleteMapping("/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteComment(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			@Parameter(description = "코멘트 id") @PathVariable UUID commentId,
			Authentication authentication) {
		reviewService.deleteComment(handoverId, commentId, currentUserId(authentication));
	}

	@Operation(summary = "인계자에게 보완 요청",
			description = """
					검토 후 인계자에게 수정을 요청한다(`PENDING_REVIEW` → `REVISION_REQUESTED`). 관리자만 가능.
					`reason`을 보내면 코멘트로도 기록한다(선택).
					- 검토 대기 상태가 아니면: `409`(code=`HANDOVER_INVALID_STATE`)
					""")
	@PostMapping("/request-revision")
	public HandoverResponse requestRevision(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			@RequestBody(required = false) RequestRevisionRequest request,
			Authentication authentication) {
		String reason = request != null ? request.reason() : null;
		return reviewService.requestRevision(handoverId, currentUserId(authentication), reason);
	}

	@Operation(summary = "관리자 최종 승인",
			description = """
					검토를 마치고 최종 승인한다(`PENDING_REVIEW` → `APPROVED`). 관리자만 가능.
					**체크리스트가 비어 있거나 미완료 항목이 있으면 승인할 수 없다**: `409`(code=`REVIEW_CHECKLIST_INCOMPLETE`).
					- 검토 대기 상태가 아니면: `409`(code=`HANDOVER_INVALID_STATE`)
					""")
	@PostMapping("/approve")
	public HandoverResponse approve(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			Authentication authentication) {
		return reviewService.approve(handoverId, currentUserId(authentication));
	}

	private UUID currentUserId(Authentication authentication) {
		return authService.getByEmail(authentication.getName()).getId();
	}
}
