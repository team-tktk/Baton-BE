package com.baton.readiness;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.baton.aiusage.AiFeature;
import com.baton.aiusage.AiTask;
import com.baton.aiusage.AiTaskLockService;
import com.baton.aiusage.AiUsageGuard;
import com.baton.handover.HandoverAccess;
import com.baton.readiness.dto.ApplyFixRequest;
import com.baton.readiness.dto.ApplyFixResponse;
import com.baton.readiness.dto.FixAnswerRequest;
import com.baton.readiness.dto.ReadinessFixResponse;
import com.baton.readiness.dto.ReadinessResponse;
import com.baton.readiness.dto.ReadinessRubricResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "06. 인수인계 준비도",
		description = "문서가 실제 업무에 쓸 만큼 준비됐는지 점수·부족한 이유를 보여주고, 부족한 항목 하나를 골라 보완·재평가한다. "
				+ "AI는 영역별 상태(충분·일부 부족·누락·충돌)만 정하고, 점수는 서버가 평가 기준 버전의 배점으로 계산한다. "
				+ "조회는 참여자 전체, 평가·보완은 인계자 전용(권한 없음 403 HANDOVER_FORBIDDEN, 인수인계 없음 404 HANDOVER_NOT_FOUND). "
				+ "권장 흐름: GET /readiness → (404 또는 stale) POST /evaluate → POST /items/{area}/fixes → "
				+ "(NEEDS_INPUT이면) PUT /fixes/{id}/answers → POST /fixes/{id}/apply.")
@RestController
@RequestMapping("/api/v1/handovers/{handoverId}/readiness")
@RequiredArgsConstructor
public class ReadinessController {

	private final ReadinessService readinessService;
	private final ReadinessFixService readinessFixService;
	private final HandoverAccess handoverAccess;
	private final AiUsageGuard aiUsageGuard;
	private final AiTaskLockService aiTaskLockService;

	@Operation(summary = "준비도 조회",
			description = """
					현재 문서의 준비도 점수와 영역별 평가를 반환한다. AI를 호출하지 않는다. 참여자 모두 가능.

					| 필드 | 설명 |
					| --- | --- |
					| score | 총점(0~100) |
					| potentialScore | 중요한 확인 항목을 모두 해결했을 때의 예상 점수 |
					| grade | READY(80점 이상) · NEEDS_IMPROVEMENT(50점 이상) · NOT_READY |
					| keyIssueCount | 중요한 확인 항목 수(최대 3) |
					| stale | true면 평가 이후 문서나 업로드 자료가 바뀐 것 → 다시 평가 필요 |
					| areas | 영역별 평가. 잃은 점수가 큰 영역부터 정렬 |

					**areas 항목**

					| 필드 | 설명 |
					| --- | --- |
					| keyIssue | true면 "중요한 확인" 카드에 보여줄 항목 |
					| status / percent | 영역 상태와 달성률 |
					| summary / resolution | 부족한 이유와 해결 방법 |
					| evidence | 근거 파일. sourceId로 원문 열기 |
					| section | 부족한 내용이 들어갈 문서 섹션("문서에서 수정하기" 이동 위치) |
					| anchorText | 문서에서 강조할 문장. 없으면 null |

					**점수 계산(서버)**
					영역 점수 = 배점 × 상태 비율(충분 100%, 일부 부족 50%, 충돌 25%, 누락 0%), 총점 = 영역 점수의 합(반올림).
					배점은 GET /readiness/rubric 에서 확인한다.

					**에러**
					- 404 READINESS_NOT_EVALUATED: 평가한 적 없음 → POST /readiness/evaluate 호출
					- 404 AI_DRAFT_NOT_FOUND: 초안 없음
					""",
			responses = @ApiResponse(responseCode = "200", description = "성공", content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = ReadinessResponse.class),
					examples = {
						@ExampleObject(name = "보완 필요(영역은 2개만 표시, 실제로는 8개)", value = """
								{
								  "evaluationId": "7f1c2a3b-...",
								  "rubricVersion": "v1",
								  "score": 59,
								  "potentialScore": 95,
								  "grade": "NEEDS_IMPROVEMENT",
								  "gradeLabel": "보완 필요",
								  "keyIssueCount": 3,
								  "stale": false,
								  "draftRevision": 4,
								  "evaluatedAt": "2026-09-16T05:30:00Z",
								  "areas": [
								    {
								      "area": "EXCEPTION", "label": "예외 대응",
								      "criteria": "오류나 특수 상황의 대응 방법이 있는가 (예외 상황별 처리 절차와 판단 기준)",
								      "weight": 15, "status": "MISSING", "statusLabel": "누락", "percent": 0, "keyIssue": true,
								      "section": "RULES_AND_EXCEPTIONS", "sectionLabel": "업무 기준과 예외",
								      "anchorText": "환불 오류 발생 시의 세부 처리 절차와 담당자가 명확하지 않다.",
								      "summary": "환불 오류 대응 담당자가 명확하지 않아요",
								      "resolution": "환불 오류 발생 시 처리 절차와 담당자를 확인해 문서에 적어주세요",
								      "evidence": [ { "sourceId": "a1b2c3d4-...", "fileName": "프로모션 운영 체크리스트.xlsx", "locator": "3번 시트, 예외 상황" } ]
								    },
								    {
								      "area": "COMPLETION", "label": "완료 기준",
								      "criteria": "업무가 끝났다고 판단할 수 있는가 (완료·인수 완료를 판단하는 기준)",
								      "weight": 10, "status": "SUFFICIENT", "statusLabel": "충분", "percent": 100, "keyIssue": false,
								      "section": "COMPLETION_CRITERIA", "sectionLabel": "완료 기준",
								      "anchorText": null, "summary": "완료 기준이 구체적으로 적혀 있어요", "resolution": null, "evidence": []
								    }
								  ]
								}
								""")
					})))
	@GetMapping
	public ReadinessResponse get(@PathVariable UUID handoverId, Authentication authentication) {
		handoverAccess.requireViewer(handoverId, authentication);
		return readinessService.getLatest(handoverId);
	}

	@Operation(summary = "준비도 평가",
			description = """
					현재 문서를 평가 기준으로 평가하고 결과를 반환한다(동기, AI 호출로 수십 초 걸릴 수 있음). 인계자만 가능.
					문서 내용·업로드 자료·평가 기준 버전이 이전 평가와 같으면 AI를 다시 부르지 않고 같은 결과를 그대로 준다
					→ 같은 내용에는 항상 같은 점수.
					- 초안 없음: 404(code=AI_DRAFT_NOT_FOUND)
					- 같은 인수인계의 평가가 이미 진행 중(중복 클릭 등): 409(code=AI_TASK_ALREADY_RUNNING)
					""")
	@PostMapping("/evaluate")
	public ReadinessResponse evaluate(@PathVariable UUID handoverId, Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		return aiTaskLockService.runExclusive(AiTask.READINESS_EVALUATION, handoverId,
				() -> readinessService.evaluate(handoverId));
	}

	@Operation(summary = "준비도 평가 기준",
			description = "현재 평가 기준 버전의 영역별 확인 내용·배점·문서 섹션, 상태별 환산 비율, 등급 경계. 참여자 모두 가능.")
	@GetMapping("/rubric")
	public ReadinessRubricResponse rubric(@PathVariable UUID handoverId, Authentication authentication) {
		handoverAccess.requireViewer(handoverId, authentication);
		return ReadinessRubricResponse.from(ReadinessRubrics.CURRENT);
	}

	@Operation(summary = "부족 항목 보완 시작",
			description = """
					현재 평가의 부족 항목(area) 하나에 대한 보완안을 만든다(동기, AI 호출). 인계자만 가능. **문서는 바뀌지 않는다.**
					업로드 자료를 검색해 사실이 있으면 수정안(status=PROPOSED, before/after 비교),
					없으면 추가 질문(status=NEEDS_INPUT, questions[])을 돌려준다.
					before/after는 문서 조회 응답의 content.{sectionField}와 같은 형식이다.
					- 평가한 적 없음: 404(code=READINESS_NOT_EVALUATED)
					- 평가 이후 문서가 바뀜: 409(code=READINESS_STALE) → 다시 평가 후 시도
					- 이미 충분한 항목: 409(code=READINESS_ITEM_SUFFICIENT)
					- 같은 인수인계의 보완안 생성이 이미 진행 중: 409(code=AI_TASK_ALREADY_RUNNING)
					- AI 요청 한도 초과(인수인계서 생성·보완안 생성·채팅 합산): 429(code=AI_USAGE_LIMIT_EXCEEDED, Retry-After 헤더·retryAt 포함)

					**before / after 형식(section별)**

					| section | 형식 |
					| --- | --- |
					| PURPOSE, COMPLETION_CRITERIA | 문자열 |
					| RULES_AND_EXCEPTIONS, FIRST_WEEK_CHECKLIST | 문자열 배열 |
					| ONGOING_TASKS, RECURRING_TASKS | TaskItem 배열 |
					| STAKEHOLDERS | Stakeholder 배열 |
					| TOOLS | ToolItem 배열 |
					| SCHEDULE | ScheduleItem 배열 |
					| ACCESS_ACCOUNTS | AccessItem 배열 |
					| CONFIRMED_CRITERIA | ConfirmedCriterion 배열 |
					""",
			responses = @ApiResponse(responseCode = "201", description = "성공", content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = ReadinessFixResponse.class),
					examples = {
						@ExampleObject(name = "자료에서 사실을 찾아 수정안을 만든 경우(PROPOSED)", value = """
								{
								  "fixId": "c9d8e7f6-...",
								  "area": "EXCEPTION", "areaLabel": "예외 대응",
								  "section": "RULES_AND_EXCEPTIONS", "sectionLabel": "업무 기준과 예외", "sectionField": "rulesAndExceptions",
								  "status": "PROPOSED",
								  "baseRevision": 4, "stale": false, "appliedRevision": null,
								  "before": ["결제 오류 시 고객센터와 협업해 환불한다."],
								  "after": ["결제 오류 시 고객센터와 협업해 환불한다.", "환불 오류는 CS팀이 당일 처리하고 결제팀에 공유한다."],
								  "changeSummary": "환불 오류 처리 담당과 공유 대상을 추가했어요",
								  "questions": [],
								  "evidence": [ { "sourceId": "a1b2c3d4-...", "fileName": "고객센터 운영 매뉴얼 v3.0.pdf", "locator": "청크 4/12" } ],
								  "createdAt": "2026-09-16T05:31:00Z", "updatedAt": "2026-09-16T05:31:00Z"
								}
								"""),
						@ExampleObject(name = "자료에 답이 없어 추가 질문을 만든 경우(NEEDS_INPUT)", value = """
								{
								  "fixId": "c9d8e7f6-...",
								  "area": "EXCEPTION", "areaLabel": "예외 대응",
								  "section": "RULES_AND_EXCEPTIONS", "sectionLabel": "업무 기준과 예외", "sectionField": "rulesAndExceptions",
								  "status": "NEEDS_INPUT",
								  "baseRevision": 4, "stale": false, "appliedRevision": null,
								  "before": ["결제 오류 시 고객센터와 협업해 환불한다."],
								  "after": null,
								  "changeSummary": null,
								  "questions": [
								    { "id": "q1", "question": "환불 오류는 누가 처리하나요?", "reason": "자료에 담당자가 없어요", "answer": null }
								  ],
								  "evidence": [],
								  "createdAt": "2026-09-16T05:31:00Z", "updatedAt": "2026-09-16T05:31:00Z"
								}
								""")
					})))
	@PostMapping("/items/{area}/fixes")
	@ResponseStatus(HttpStatus.CREATED)
	public ReadinessFixResponse createFix(
			@PathVariable UUID handoverId,
			@PathVariable ReadinessArea area,
			Authentication authentication) {
		UUID userId = handoverAccess.requireOwner(handoverId, authentication);
		return aiTaskLockService.runExclusive(AiTask.READINESS_FIX, handoverId, () -> readinessFixService.create(
				handoverId, area, () -> aiUsageGuard.acquire(userId, AiFeature.READINESS_FIX, handoverId)));
	}

	@Operation(summary = "보완안 조회",
			description = """
					보완안의 상태·수정 전후·추가 질문·근거를 반환한다. 인계자만 가능.
					stale=true면 보완안을 만든 뒤 문서가 바뀐 것이라 적용할 수 없다 → 새 보완안을 만든다.
					- 없는 보완안: 404(code=READINESS_FIX_NOT_FOUND)
					""")
	@GetMapping("/fixes/{fixId}")
	public ReadinessFixResponse getFix(
			@PathVariable UUID handoverId,
			@PathVariable UUID fixId,
			Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		return readinessFixService.get(handoverId, fixId);
	}

	@Operation(summary = "보완안 추가 질문 답변",
			description = """
					NEEDS_INPUT 보완안의 추가 질문에 답하면, 자료와 답변을 함께 반영해 수정안을 다시 만든다(동기, AI 호출). 인계자만 가능.
					답한 질문만 보내면 된다. 여전히 정보가 부족하면 새 질문이 붙은 NEEDS_INPUT으로 돌아온다.

					- 없는 질문 id: 400(code=BAD_REQUEST)
					- 이미 적용·취소한 보완안: 409(code=READINESS_FIX_INVALID_STATE)
					- 그사이 문서가 바뀜: 409(code=AI_DRAFT_REVISION_CONFLICT)
					- 같은 인수인계의 보완안 생성이 이미 진행 중: 409(code=AI_TASK_ALREADY_RUNNING)
					- AI 요청 한도 초과(인수인계서 생성·보완안 생성·채팅 합산): 429(code=AI_USAGE_LIMIT_EXCEEDED, Retry-After 헤더·retryAt 포함)
					""",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = FixAnswerRequest.class),
					examples = {
						@ExampleObject(name = "기본", value = """
								{ "answers": [ { "questionId": "q1", "answer": "환불 오류는 CS팀 박지민 매니저가 처리하고, 결제팀에 당일 공유합니다." } ] }
								""")
					})))
	@PutMapping("/fixes/{fixId}/answers")
	public ReadinessFixResponse answerFix(
			@PathVariable UUID handoverId,
			@PathVariable UUID fixId,
			@Valid @RequestBody FixAnswerRequest request,
			Authentication authentication) {
		UUID userId = handoverAccess.requireOwner(handoverId, authentication);
		return aiTaskLockService.runExclusive(AiTask.READINESS_FIX, handoverId, () -> readinessFixService.answer(
				handoverId, fixId, request, () -> aiUsageGuard.acquire(userId, AiFeature.READINESS_FIX, handoverId)));
	}

	@Operation(summary = "보완안 적용",
			description = """
					사용자가 확인한 수정안(PROPOSED)을 문서에 적용한다. 인계자만 가능.
					대상 섹션(section) 하나만 바꾸고 나머지 섹션은 그대로 둔다. 적용 후 최신 문서로 준비도를 다시 평가해 함께 반환한다.
					readiness가 null이면 재평가만 실패한 것이므로 POST /readiness/evaluate로 다시 평가하면 된다.

					응답 ApplyFixResponse:
					- fix: 적용된 보완안(status=APPLIED, appliedRevision=적용 후 문서 버전)
					- document: 적용 후 최신 문서(GET /document와 같은 형식, revision은 1 증가)
					- readiness: 재평가 결과(GET /readiness와 같은 형식) 또는 null

					- 사용자가 본 버전과 현재 문서 버전이 다름: 409(code=AI_DRAFT_REVISION_CONFLICT) — 적용하지 않는다.
					- 수정안이 없는 상태(NEEDS_INPUT)·이미 적용/취소: 409(code=READINESS_FIX_INVALID_STATE)
					""",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = ApplyFixRequest.class),
					examples = {
						@ExampleObject(name = "보완안 응답의 baseRevision 그대로 전송", value = """
								{ "baseRevision": 4 }
								""")
					})))
	@PostMapping("/fixes/{fixId}/apply")
	public ApplyFixResponse applyFix(
			@PathVariable UUID handoverId,
			@PathVariable UUID fixId,
			@Valid @RequestBody ApplyFixRequest request,
			Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		return readinessFixService.apply(handoverId, fixId, request.baseRevision());
	}

	@Operation(summary = "보완안 취소",
			description = """
					보완안을 적용하지 않고 닫는다("문서에서 수정하기"로 직접 고칠 때 등). 문서는 바뀌지 않는다. 인계자만 가능.
					직접 수정한 뒤에는 PATCH /document 저장 → POST /readiness/evaluate로 재평가한다.
					- 이미 적용·취소한 보완안: 409(code=READINESS_FIX_INVALID_STATE)
					""")
	@PostMapping("/fixes/{fixId}/discard")
	public ReadinessFixResponse discardFix(
			@PathVariable UUID handoverId,
			@PathVariable UUID fixId,
			Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		return readinessFixService.discard(handoverId, fixId);
	}
}
