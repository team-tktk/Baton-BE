package com.baton.masking;

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

import com.baton.masking.dto.CandidateDecisionRequest;
import com.baton.masking.dto.ManualCandidateRequest;
import com.baton.masking.dto.MaskingCandidateResponse;
import com.baton.masking.dto.MaskingReviewResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "07. 마스킹 검수",
		description = "파일 업로드와 AI 분석 사이에서, 서버가 찾은 민감정보(이메일·전화번호·계좌번호 등) 후보를 사용자가 확인하고 "
				+ "적용/해제하거나 직접 추가한다. 원문이 나오므로 인계자 전용(권한 없음 403 HANDOVER_FORBIDDEN). "
				+ "파일 상태가 MASKING_REVIEW일 때만 수정할 수 있다(아니면 409 MASKING_NOT_IN_REVIEW). "
				+ "권장 흐름: GET /files(remainingReviewCount 확인) → 파일별 GET /masking → PATCH·POST·DELETE /masking/candidates → "
				+ "POST /masking/confirm(검수 확정) → 모든 파일 확정 후 POST /analysis.")
@RestController
@RequestMapping("/api/v1/handovers/{handoverId}/files/{fileId}/masking")
@RequiredArgsConstructor
public class MaskingController {

	private final MaskingService maskingService;
	private final MaskingAccess maskingAccess;

	@Operation(summary = "마스킹 검수 화면 조회",
			description = """
					파일의 원문 텍스트와 마스킹 후보 목록, 상단 요약 숫자를 반환한다. 인계자만 가능.

					| 필드 | 설명 |
					| --- | --- |
					| status | 파일 처리 상태. MASKING_REVIEW일 때만 수정 가능 |
					| confirmed | 검수를 확정했는지 |
					| text | 원문 텍스트. MASKING_REVIEW일 때만 내려주고, 그 외에는 null |
					| summary.total | 전체 후보 수 |
					| summary.autoMasked | 자동으로 찾았고 확인이 필요 없는 항목("자동 마스킹 N건") |
					| summary.needsReview | 확인이 필요한 항목("확인 필요 N건") |
					| summary.remaining | 확인이 필요한데 아직 적용/해제를 누르지 않은 항목. 0이어야 확정 가능 |
					| summary.applied | 확정 시 실제로 가려질 항목 수 |
					| candidates | 원문 위치 순으로 정렬 |

					**candidates 항목**

					| 필드 | 설명 |
					| --- | --- |
					| type / typeLabel | EMAIL·PHONE·ACCOUNT·RRN·CARD·BUSINESS_NO·CUSTOM / 화면 표시 이름 |
					| origin | DETECTED(자동) · MANUAL(직접 추가) |
					| startOffset, endOffset | text 기준 [start, end) 구간. text.substring(start, end)가 하이라이트 대상 |
					| confidencePercent | 규칙 기반 신뢰도(%). 직접 추가는 100 |
					| applied | 체크박스 상태(확정 시 가릴지) |
					| needsReview | 확인이 필요한 항목인지("확인 필요" 뱃지) |
					| pendingReview | 확인이 필요한데 아직 누르지 않았는지 |
					| preview | 목록 표시용 부분 가림 값 |

					**에러**
					- 404 AI_SOURCE_DOCUMENT_NOT_FOUND: 없는 파일(또는 다른 인수인계의 파일)
					""",
			responses = @ApiResponse(responseCode = "200", description = "성공", content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = MaskingReviewResponse.class),
					examples = @ExampleObject(name = "검수 대기(확인 필요 1건 남음)", value = """
							{
							  "fileId": "9b2f1c3a-...",
							  "fileName": "업무협약서.docx",
							  "status": "MASKING_REVIEW",
							  "confirmed": false,
							  "text": "담당자 이메일 : minji.kim@example.com\\n참고 번호 110-123-456789",
							  "summary": { "total": 2, "autoMasked": 1, "needsReview": 1, "remaining": 1, "applied": 2 },
							  "candidates": [
							    {
							      "id": "c1a2...", "type": "EMAIL", "typeLabel": "이메일", "origin": "DETECTED",
							      "startOffset": 10, "endOffset": 31, "confidencePercent": 98,
							      "applied": true, "needsReview": false, "pendingReview": false, "preview": "min***@example.com"
							    },
							    {
							      "id": "c3d4...", "type": "ACCOUNT", "typeLabel": "계좌번호", "origin": "DETECTED",
							      "startOffset": 38, "endOffset": 52, "confidencePercent": 60,
							      "applied": true, "needsReview": true, "pendingReview": true, "preview": "***-***-**6789"
							    }
							  ]
							}
							"""))))
	@GetMapping
	public MaskingReviewResponse getReview(
			@PathVariable UUID handoverId,
			@PathVariable UUID fileId,
			Authentication authentication) {
		maskingAccess.requireOwner(handoverId, authentication);
		return maskingService.getReview(handoverId, fileId);
	}

	@Operation(summary = "마스킹 항목 적용/해제",
			description = """
					체크박스를 켜거나(applied=true) 끈다(applied=false). 어느 쪽이든 "확인 완료"로 기록되어 pendingReview가 false가 된다.
					변경된 항목을 반환한다. 요약 숫자가 필요하면 GET /masking을 다시 호출한다. 인계자만 가능.

					**에러**
					- 409 MASKING_NOT_IN_REVIEW: 검수 대기 상태가 아닌 파일
					- 404 MASKING_CANDIDATE_NOT_FOUND: 없는 항목(또는 다른 파일의 항목)
					- 400 VALIDATION_FAILED: applied 누락
					""")
	@PatchMapping("/candidates/{candidateId}")
	public MaskingCandidateResponse decide(
			@PathVariable UUID handoverId,
			@PathVariable UUID fileId,
			@PathVariable UUID candidateId,
			@Valid @RequestBody CandidateDecisionRequest request,
			Authentication authentication) {
		maskingAccess.requireOwner(handoverId, authentication);
		return maskingService.decide(handoverId, fileId, candidateId, request.applied());
	}

	@Operation(summary = "마스킹 구간 직접 추가",
			description = """
					자동으로 찾지 못한 정보(이름·주소 등)를 사용자가 원문에서 선택해 추가한다. 인계자만 가능. 성공: 201.
					- startOffset, endOffset: GET /masking의 text 기준 [start, end) 구간
					- type: 생략하면 CUSTOM(직접 마스킹)
					- 추가된 항목은 적용(applied=true)·확인 완료 상태로 저장된다.

					**에러**
					- 409 MASKING_NOT_IN_REVIEW: 검수 대기 상태가 아닌 파일
					- 400 MASKING_INVALID_RANGE: 문서 범위를 벗어났거나 start ≥ end, 또는 공백만 있는 구간
					- 409 MASKING_RANGE_OVERLAP: 이미 항목이 있는 구간과 겹침(자동 항목이면 체크를 켜서 사용)
					""",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
					mediaType = "application/json",
					examples = @ExampleObject(name = "이름 직접 마스킹", value = """
							{ "startOffset": 4, "endOffset": 7, "type": "CUSTOM" }
							"""))))
	@PostMapping("/candidates")
	@ResponseStatus(HttpStatus.CREATED)
	public MaskingCandidateResponse addManual(
			@PathVariable UUID handoverId,
			@PathVariable UUID fileId,
			@Valid @RequestBody ManualCandidateRequest request,
			Authentication authentication) {
		maskingAccess.requireOwner(handoverId, authentication);
		return maskingService.addManual(handoverId, fileId, request);
	}

	@Operation(summary = "마스킹 검수 확정",
			description = """
					확인이 끝난 파일의 검수를 확정한다. 인계자만 가능. 확정 후의 검수 화면 데이터(MaskingReviewResponse)를 반환한다.

					**처리 순서**
					1. 적용(applied=true)된 구간을 [유형#번호] 토큰으로 바꾼 텍스트를 만든다.
					   같은 파일에서 같은 값은 같은 번호(예: 두 곳의 같은 이메일 → 둘 다 [이메일#1]). 직접 추가한 구간은 [비공개#번호].
					2. 원문을 이 텍스트로 교체하고 원문은 DB에서 삭제한다. 확정 시각을 기록한다(status=INDEXING).
					3. 마스킹된 텍스트만 임베딩한다 → status=INDEXED. 이후 분석·초안·질의응답은 마스킹된 텍스트만 사용한다.

					확정 후에는 되돌릴 수 없다(원문이 삭제됨). 응답의 text는 null, confirmed는 true.
					모든 파일이 확정되어야 AI 분석을 시작할 수 있다(POST /analysis → 409 MASKING_NOT_CONFIRMED).

					**에러**
					- 409 MASKING_REVIEW_INCOMPLETE: 확인하지 않은 항목(pendingReview)이 남음. detail에 남은 개수
					- 409 MASKING_NOT_IN_REVIEW: 검수 대기 상태가 아님(이미 확정했거나 처리 중)
					- 422 AI_FILE_PARSE_FAILED: 3번 임베딩 실패. 1·2번은 이미 반영되어 status=FAILED가 되며,
					  POST /files/{fileId}/retry로 재처리하면 원문 추출 없이 임베딩만 다시 한다.
					- 404 AI_SOURCE_DOCUMENT_NOT_FOUND: 없는 파일(또는 다른 인수인계의 파일)
					""")
	@PostMapping("/confirm")
	public MaskingReviewResponse confirm(
			@PathVariable UUID handoverId,
			@PathVariable UUID fileId,
			Authentication authentication) {
		maskingAccess.requireOwner(handoverId, authentication);
		maskingService.confirm(handoverId, fileId);
		return maskingService.getReview(handoverId, fileId);
	}

	@Operation(summary = "직접 추가한 마스킹 구간 삭제",
			description = """
					사용자가 직접 추가한(origin=MANUAL) 항목을 삭제한다. 인계자만 가능. 성공: 204 No Content.

					**에러**
					- 409 MASKING_NOT_IN_REVIEW: 검수 대기 상태가 아닌 파일
					- 409 MASKING_CANDIDATE_NOT_DELETABLE: 자동으로 찾은 항목(삭제 대신 applied=false로 해제)
					- 404 MASKING_CANDIDATE_NOT_FOUND: 없는 항목(또는 다른 파일의 항목)
					""")
	@DeleteMapping("/candidates/{candidateId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteManual(
			@PathVariable UUID handoverId,
			@PathVariable UUID fileId,
			@PathVariable UUID candidateId,
			Authentication authentication) {
		maskingAccess.requireOwner(handoverId, authentication);
		maskingService.deleteManual(handoverId, fileId, candidateId);
	}
}
