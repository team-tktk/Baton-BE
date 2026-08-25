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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 인수인계 기본 정보 CRUD. 목록(sent/received/reviews)과 제출/검토는 이후 슬라이스에서 추가된다.
 * 현재 로그인 사용자는 세션 인증(Authentication)에서 이메일 → User로 풀어 id를 얻는다.
 */
@Tag(name = "03. 인수인계", description = "인수인계 기본 정보 CRUD와 역할별 목록(보낸/받은/검토), 상태 전이(제출·수신확인·완료).")
@RestController
@RequestMapping("/api/v1/handovers")
@RequiredArgsConstructor
public class HandoverController {

	private final HandoverService handoverService;
	private final AuthService authService;

	@Operation(summary = "인수인계 초안 생성",
			description = """
					인계자가 새 인수인계 초안(`DRAFT`)을 만든다. 제목·인수자·관리자·업무범위를 함께 넘기면 초기값으로 채운다.
					생성자가 곧 인계자(owner)가 된다. 모든 필드는 선택 — 빈 초안을 먼저 만들고 이후 `PATCH`로 채워도 된다.
					`recipientIds`/`reviewerIds`는 `GET /members` 검색 결과의 사용자 id를 사용한다.

					**제목(`title`) 정책** — 프론트 생성 화면에 제목 입력란이 없어 **선택값**이며, 백엔드가 다음 순서로 채운다:
					1) 제목을 보내면 그대로 사용,
					2) 생략하면 첫 번째 업무범위(`workScopes[0].title`)를 제목으로 자동 생성,
					3) 업무범위도 없으면 기본 제목(`"제목 없는 인수인계"`). 이후 `PATCH`로 언제든 변경 가능.

					- 성공: `201 Created`
					- 존재하지 않는 사용자를 참여자로 지정: `400`(code=`HANDOVER_INVALID_PARTICIPANT`)

					요청 예시:
					```json
					{
					  "title": "가을 정기 할인전 운영 인수인계",
					  "recipientIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
					  "reviewerIds": ["9c1f2e30-1a2b-4c3d-8e4f-5a6b7c8d9e0f"],
					  "workScopes": [
					    { "title": "프로모션 운영", "description": "쿠폰 발행과 할인전 세팅" }
					  ]
					}
					```
					""")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public HandoverResponse create(
			@Valid @RequestBody CreateHandoverRequest request,
			Authentication authentication) {
		return handoverService.create(currentUserId(authentication), request);
	}

	@Operation(summary = "보낸 인수인계 목록",
			description = """
					로그인 사용자가 **인계자(owner)로 만든** 인수인계를 조회한다. 필터 탭 뱃지용 상태별 개수(`statusCounts`, 키=HandoverStatus 이름)를 함께 내린다.

					각 항목(`HandoverSummaryResponse`)은 목록 카드를 바로 그릴 수 있게 다음을 담는다:
					`owner`(보낸 사람 이름·팀·직책), `workScopeSummary`(대표 업무명 = 첫 업무범위 제목), `workScopeCount`(업무 개수),
					`fileCount`(첨부 개수), `recipientCount`, `status`, `submittedAt`(제출일)/`createdAt`/`updatedAt`. `receiptStatus`는 보낸 목록에선 null.
					""")
	@GetMapping("/sent")
	public HandoverListResponse listSent(
			@Parameter(description = "본 상태 필터(DRAFT/ANALYZING/.../COMPLETED). 생략 시 전체.")
			@RequestParam(required = false) HandoverStatus status,
			@Parameter(description = "직전 페이지 마지막 인수인계 id. 첫 페이지는 생략.")
			@RequestParam(required = false) UUID cursor,
			@Parameter(description = "페이지 크기(기본 20, 최대 100).")
			@RequestParam(required = false, defaultValue = "20") int size,
			Authentication authentication) {
		return handoverService.listSent(currentUserId(authentication), status, cursor, size);
	}

	@Operation(summary = "받은 인수인계 목록",
			description = """
					로그인 사용자가 **인수자(recipient)로 받은** 인수인계를 조회한다. 필터는 본 상태가 아니라 인수자 관점 단계다:
					- `UNREAD`: 아직 열어보지 않음(미완료)
					- `IN_PROGRESS`: 열어봤고 진행 중(미완료)
					- `COMPLETED`: 인수인계 완료

					`statusCounts`도 이 세 버킷(키=UNREAD/IN_PROGRESS/COMPLETED) 기준으로 내린다.
					각 항목은 보낸 목록과 동일한 요약 필드(`owner`·`workScopeSummary`·`fileCount` 등)를 담고,
					`receiptStatus`에는 **현재 사용자(인수자)의 수신 상태(UNREAD/READ)** 가 채워진다.
					""")
	@GetMapping("/received")
	public HandoverListResponse listReceived(
			@Parameter(description = "인수자 관점 필터: UNREAD · IN_PROGRESS · COMPLETED. 생략 시 전체.")
			@RequestParam(required = false) ReceivedFilter status,
			@Parameter(description = "직전 페이지 마지막 인수인계 id. 첫 페이지는 생략.")
			@RequestParam(required = false) UUID cursor,
			@Parameter(description = "페이지 크기(기본 20, 최대 100).")
			@RequestParam(required = false, defaultValue = "20") int size,
			Authentication authentication) {
		return handoverService.listReceived(currentUserId(authentication), status, cursor, size);
	}

	@Operation(summary = "관리자 검토 목록",
			description = """
					로그인 사용자가 **관리자(reviewer)로 지정된** 인수인계를 조회한다. 검토 탭(PENDING_REVIEW/REVISION_REQUESTED/APPROVED)에서 쓴다.
					`statusCounts`는 본 상태(HandoverStatus 이름) 기준.
					""")
	@GetMapping("/reviews")
	public HandoverListResponse listReviews(
			@Parameter(description = "본 상태 필터. 검토 화면은 보통 PENDING_REVIEW/REVISION_REQUESTED/APPROVED를 쓴다. 생략 시 전체.")
			@RequestParam(required = false) HandoverStatus status,
			@Parameter(description = "직전 페이지 마지막 인수인계 id. 첫 페이지는 생략.")
			@RequestParam(required = false) UUID cursor,
			@Parameter(description = "페이지 크기(기본 20, 최대 100).")
			@RequestParam(required = false, defaultValue = "20") int size,
			Authentication authentication) {
		return handoverService.listReviews(currentUserId(authentication), status, cursor, size);
	}

	@Operation(summary = "인수인계 상세 조회",
			description = """
					헤더(제목·상태·시각)·참여자·업무범위와 함께, 요청자 기준 권한 플래그(`viewerRole`)를 반환한다. 참여자(인계자/인수자/관리자) 모두 가능.

					`owner`와 `participants[]`는 `userId`뿐 아니라 이름·팀·직책까지 담는다:
					`participants[]` = `{ userId, name, team, position, role(RECIPIENT/REVIEWER), receiptStatus(인수자면 UNREAD/READ, 관리자면 null) }`.
					→ 인계자(owner)/인수자/관리자를 별도 사용자 조회 없이 표시할 수 있다.

					- 참여자가 아니면: `403`(code=`HANDOVER_FORBIDDEN`)
					- 없는 id: `404`(code=`HANDOVER_NOT_FOUND`)
					""")
	@GetMapping("/{handoverId}")
	public HandoverResponse get(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			Authentication authentication) {
		return handoverService.getForViewer(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "인수인계 기본 정보 수정",
			description = """
					인계자가 **`DRAFT` 단계에서만** 제목·인수자·관리자·업무범위를 수정한다. null로 보낸 필드는 변경하지 않는다(부분 수정).
					문서 본문 수정은 `PATCH /{id}/document`를 쓴다.
					- 제출 이후 단계에서 호출: `409`(code=`HANDOVER_NOT_EDITABLE`)
					- 인계자가 아니면: `403`(code=`HANDOVER_FORBIDDEN`)
					""")
	@PatchMapping("/{handoverId}")
	public HandoverResponse update(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			@Valid @RequestBody UpdateHandoverRequest request,
			Authentication authentication) {
		return handoverService.update(handoverId, currentUserId(authentication), request);
	}

	@Operation(summary = "인수인계 제출",
			description = """
					인계자가 인수자에게 전달하고 관리자 검토를 시작한다(→ `PENDING_REVIEW`). **멱등**: 이미 제출됐으면 상태를 바꾸지 않고 그대로 반환.
					- 제출 가능 단계는 `EDITING` 또는 `REVISION_REQUESTED`. 그 외 상태: `409`(code=`HANDOVER_INVALID_STATE`)
					""")
	@PostMapping("/{handoverId}/submit")
	public HandoverResponse submit(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			Authentication authentication) {
		return handoverService.submit(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "수신 확인",
			description = """
					인수자가 문서를 처음 열었을 때 호출해 수신 상태를 `READ`로 바꾼다(본 상태와 별개). **멱등**.
					- 인수자가 아니면: `403`(code=`HANDOVER_FORBIDDEN`)
					""")
	@PostMapping("/{handoverId}/acknowledge")
	public HandoverResponse acknowledge(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			Authentication authentication) {
		return handoverService.acknowledge(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "인수인계 완료 처리",
			description = """
					인수자가 인수인계를 완료 처리한다(→ `COMPLETED`). **멱등**.
					- 제출된 적 없는 건: `409`(code=`HANDOVER_INVALID_STATE`)
					- 인수자가 아니면: `403`(code=`HANDOVER_FORBIDDEN`)
					""")
	@PostMapping("/{handoverId}/complete")
	public HandoverResponse complete(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			Authentication authentication) {
		return handoverService.complete(handoverId, currentUserId(authentication));
	}

	@Operation(summary = "인수인계 초안 삭제",
			description = """
					인계자가 **제출 전(`DRAFT`) 초안만** 삭제한다. 성공: `204 No Content`.
					- 제출 이후: `409`(code=`HANDOVER_NOT_EDITABLE`)
					""")
	@DeleteMapping("/{handoverId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
			@Parameter(description = "인수인계 id") @PathVariable UUID handoverId,
			Authentication authentication) {
		handoverService.delete(handoverId, currentUserId(authentication));
	}

	/** 세션 인증 principal(이메일)로 현재 사용자 id를 조회한다. */
	private UUID currentUserId(Authentication authentication) {
		return authService.getByEmail(authentication.getName()).getId();
	}
}
