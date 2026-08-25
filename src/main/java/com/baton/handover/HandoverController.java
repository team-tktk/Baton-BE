package com.baton.handover;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.baton.auth.AuthService;
import com.baton.handover.dto.CreateHandoverRequest;
import com.baton.handover.dto.HandoverListResponse;
import com.baton.handover.dto.HandoverResponse;
import com.baton.handover.dto.UpdateHandoverRequest;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 인수인계 기본 정보 CRUD. 목록(sent/received/reviews)과 제출/검토는 이후 슬라이스에서 추가된다.
 * 현재 로그인 사용자는 세션 인증(Authentication)에서 이메일 → User로 풀어 id를 얻는다.
 */
@RestController
@RequestMapping("/api/v1/handovers")
@RequiredArgsConstructor
public class HandoverController {

	private final HandoverService handoverService;
	private final AuthService authService;

	@Operation(summary = "인수인계 초안 생성", description = "인계자가 새 인수인계 초안(DRAFT)을 만든다.")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public HandoverResponse create(
			@Valid @RequestBody CreateHandoverRequest request,
			Authentication authentication) {
		return handoverService.create(currentUserId(authentication), request);
	}

	@Operation(summary = "보낸 인수인계 목록", description = "인계자가 만든 인수인계를 상태 필터·커서 페이지네이션으로 조회한다.")
	@GetMapping("/sent")
	public HandoverListResponse listSent(
			@RequestParam(required = false) HandoverStatus status,
			@RequestParam(required = false) UUID cursor,
			@RequestParam(required = false, defaultValue = "20") int size,
			Authentication authentication) {
		return handoverService.listSent(currentUserId(authentication), status, cursor, size);
	}

	@Operation(summary = "받은 인수인계 목록", description = "인수자가 받은 인수인계를 상태 필터·커서 페이지네이션으로 조회한다.")
	@GetMapping("/received")
	public HandoverListResponse listReceived(
			@RequestParam(required = false) HandoverStatus status,
			@RequestParam(required = false) UUID cursor,
			@RequestParam(required = false, defaultValue = "20") int size,
			Authentication authentication) {
		return handoverService.listReceived(currentUserId(authentication), status, cursor, size);
	}

	@Operation(summary = "인수인계 상세 조회", description = "헤더/참여자/업무범위와 요청자 기준 권한을 반환한다.")
	@GetMapping("/{handoverId}")
	public HandoverResponse get(
			@PathVariable UUID handoverId,
			Authentication authentication) {
		return handoverService.getForViewer(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "인수인계 기본 정보 수정", description = "인계자가 DRAFT 단계에서 제목/참여자/업무범위를 수정한다.")
	@PatchMapping("/{handoverId}")
	public HandoverResponse update(
			@PathVariable UUID handoverId,
			@Valid @RequestBody UpdateHandoverRequest request,
			Authentication authentication) {
		return handoverService.update(handoverId, currentUserId(authentication), request);
	}

	@Operation(summary = "인수인계 제출", description = "인계자가 인수자에게 전달하고 관리자 검토를 시작한다(→ PENDING_REVIEW). 멱등.")
	@PostMapping("/{handoverId}/submit")
	public HandoverResponse submit(
			@PathVariable UUID handoverId,
			Authentication authentication) {
		return handoverService.submit(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "수신 확인", description = "인수자가 문서를 처음 열어 수신 상태를 READ로 바꾼다. 멱등.")
	@PostMapping("/{handoverId}/acknowledge")
	public HandoverResponse acknowledge(
			@PathVariable UUID handoverId,
			Authentication authentication) {
		return handoverService.acknowledge(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "인수인계 초안 삭제", description = "인계자가 제출 전(DRAFT) 초안을 삭제한다.")
	@DeleteMapping("/{handoverId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
			@PathVariable UUID handoverId,
			Authentication authentication) {
		handoverService.delete(handoverId, currentUserId(authentication));
	}

	/** 세션 인증 principal(이메일)로 현재 사용자 id를 조회한다. */
	private UUID currentUserId(Authentication authentication) {
		return authService.getByEmail(authentication.getName()).getId();
	}
}
