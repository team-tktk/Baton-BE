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
import com.baton.readiness.dto.CreateFixRequest;
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
		description = "문서가 실제 업무에 쓸 만큼 준비됐는지 점수·부족한 이유를 보여주고, 부족한 영역들을 한 번에 보완·재평가한다. "
				+ "AI는 영역별 상태(충분·일부 부족·누락·충돌)만 정하고, 점수는 서버가 평가 기준 버전의 배점으로 계산한다. "
				+ "조회는 참여자 전체, 평가·보완은 인계자 전용(권한 없음 403 HANDOVER_FORBIDDEN, 인수인계 없음 404 HANDOVER_NOT_FOUND). "
				+ "권장 흐름: GET /readiness → (404 또는 stale) POST /evaluate → POST /fixes {areas} → "
				+ "PUT /fixes/{id}/answers(질문이 있으면) → POST /fixes/{id}/generate(AI 사용량 1회) → POST /fixes/{id}/apply.")
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
					| grade | READY(80점 이상) · NEEDS_IMPROVEMENT(50점 이상) · NOT_READY |
					| keyIssueCount | 중요한 확인 항목 수(최대 3) |
					| stale | true면 평가 이후 문서나 업로드 자료가 바뀐 것 → 다시 평가 필요 |
					| areas | 영역별 평가. 잃은 점수가 큰 영역부터 정렬 |
					| deferredQuestionCount | 확인 질문 단계에서 "나중에 답하기"로 미룬 질문 수(전 영역 합계) |

					**areas 항목**

					| 필드 | 설명 |
					| --- | --- |
					| keyIssue | true면 "중요한 확인" 카드에 보여줄 항목 |
					| status / percent | 영역 상태와 달성률 |
					| summary / resolution | 부족한 이유와 해결 방법 |
					| evidence | 근거 파일. sourceId로 원문 열기 |
					| section | "문서에서 수정하기" 이동 위치(targetSections의 첫 번째) |
					| targetSections | 보완안이 고칠 문서 섹션(해결 방법이 가리키는 곳). 충돌이면 확인된 업무 기준 포함 |
					| questions | 보완할 때 물을 질문(자료에 답이 없는 것만). 충돌이면 "어느 쪽이 맞나요?" + options(자료별 값) |
					| anchorText | 문서에서 강조할 문장. 없으면 null |
					| deferredQuestions | 이 영역에 붙은 "나중에 답하기" 질문(중요도순). 없으면 빈 배열 |

					**나중에 답하기 질문(deferredQuestions)**
					- 표시만 한다. 점수·상태·중요한 확인 개수에는 영향이 없다.
					- 영역은 질문의 첫 번째 반영 위치(targetSections)가 속한 영역이다.
					- 평가 결과와 달리 매번 현재 상태로 읽는다. 답하면 바로 목록에서 빠진다(다시 평가할 필요 없음).
					- 답하기(인계자만): PUT /questions/{questionId}/answer 로 처리한 뒤 POST /questions/apply 로 문서에 반영한다.

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
								  "rubricVersion": "v2",
								  "score": 59,
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
								      "evidence": [ { "sourceId": "a1b2c3d4-...", "fileName": "프로모션 운영 체크리스트.xlsx", "locator": "3번 시트, 예외 상황" } ],
								      "targetSections": [ { "section": "RULES_AND_EXCEPTIONS", "field": "rulesAndExceptions", "label": "업무 기준과 예외" } ],
								      "questions": [ { "question": "환불 오류는 누가 처리하나요?", "reason": "자료에 담당자가 없어요", "options": [] } ],
								      "deferredQuestions": [
								        {
								          "id": "5e6f7a8b-...", "type": "INTERVIEW",
								          "questionText": "환불 오류 발생 시 최종 처리 담당자는 누구인가요?",
								          "reason": "모르면 환불 문의가 방치돼요",
								          "area": "EXCEPTION",
								          "targetSections": [ { "section": "RULES_AND_EXCEPTIONS", "field": "rulesAndExceptions", "label": "업무 기준과 예외" } ]
								        }
								      ]
								    },
								    {
								      "area": "COMPLETION", "label": "완료 기준",
								      "criteria": "업무가 끝났다고 판단할 수 있는가 (완료·인수 완료를 판단하는 기준)",
								      "weight": 10, "status": "SUFFICIENT", "statusLabel": "충분", "percent": 100, "keyIssue": false,
								      "section": "COMPLETION_CRITERIA", "sectionLabel": "완료 기준",
								      "anchorText": null, "summary": "완료 기준이 구체적으로 적혀 있어요", "resolution": null, "evidence": [],
								      "targetSections": [ { "section": "COMPLETION_CRITERIA", "field": "completionCriteria", "label": "완료 기준" } ],
								      "questions": [],
								      "deferredQuestions": []
								    }
								  ],
								  "deferredQuestionCount": 1
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

	@Operation(summary = "보완 시작(여러 영역 한 번에)",
			description = """
					현재 평가의 부족 영역들(areas)을 한 번에 보완하는 보완안을 만든다. 인계자만 가능.
					**AI를 부르지 않고 사용량도 차감하지 않는다. 문서는 바뀌지 않는다.**

					영역마다 인계자에게 물을 질문(areas[].questions)을 모아 준다.
					- 평가가 만든 질문(자료에 답이 없는 것만. 충돌이면 "어느 쪽이 맞나요?" + options)
					- 확인 질문 단계에서 "나중에 답하기"로 미룬 같은 영역의 질문(clarificationQuestionId 있음)
					질문이 없는 영역도 있다(자료로 채울 수 있음). 답한 뒤 POST /fixes/{fixId}/generate로 수정안을 만든다.

					- 평가한 적 없음: 404(code=READINESS_NOT_EVALUATED)
					- 평가 이후 문서가 바뀜: 409(code=READINESS_STALE) → 다시 평가 후 시도
					- 이미 충분한 영역이 섞여 있음: 409(code=READINESS_ITEM_SUFFICIENT)
					""",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = CreateFixRequest.class),
					examples = {
						@ExampleObject(name = "중요한 확인 3개를 한 번에", value = """
								{ "areas": ["EXCEPTION", "PROCEDURE", "ACCESS"] }
								""")
					})),
			responses = @ApiResponse(responseCode = "201", description = "성공", content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = ReadinessFixResponse.class),
					examples = {
						@ExampleObject(name = "질문 답변 대기(NEEDS_INPUT)", value = """
								{
								  "fixId": "c9d8e7f6-...",
								  "status": "NEEDS_INPUT",
								  "baseRevision": 4, "stale": false, "appliedRevision": null,
								  "areas": [
								    {
								      "area": "EXCEPTION", "areaLabel": "예외 대응", "status": "CONFLICT", "statusLabel": "충돌",
								      "sections": [
								        { "section": "RULES_AND_EXCEPTIONS", "field": "rulesAndExceptions", "label": "업무 기준과 예외" },
								        { "section": "CONFIRMED_CRITERIA", "field": "confirmedCriteria", "label": "확인된 업무 기준" }
								      ],
								      "proposed": false, "changeSummary": null, "evidence": [],
								      "questions": [
								        { "id": "q1", "area": "EXCEPTION", "question": "환불 승인은 누가 하나요?", "reason": "자료마다 승인자가 달라요",
								          "options": ["운영 매뉴얼.pdf: 팀장 승인", "체크리스트.xlsx: 마케팅 확인 후 팀장 승인"],
								          "clarificationQuestionId": null, "answer": null }
								      ]
								    },
								    {
								      "area": "PROCEDURE", "areaLabel": "실행 절차", "status": "PARTIAL", "statusLabel": "일부 부족",
								      "sections": [ { "section": "RECURRING_TASKS", "field": "recurringTasks", "label": "반복 업무" } ],
								      "proposed": false, "changeSummary": null, "evidence": [], "questions": []
								    }
								  ],
								  "sections": [
								    { "section": "RULES_AND_EXCEPTIONS", "field": "rulesAndExceptions", "label": "업무 기준과 예외",
								      "before": ["결제 오류 시 고객센터와 협업해 환불한다."], "after": null, "changed": false },
								    { "section": "CONFIRMED_CRITERIA", "field": "confirmedCriteria", "label": "확인된 업무 기준",
								      "before": [], "after": null, "changed": false },
								    { "section": "RECURRING_TASKS", "field": "recurringTasks", "label": "반복 업무",
								      "before": [ { "title": "할인 코드 설정", "status": "매주 반복", "description": "운영툴에서 할인 코드 생성" } ], "after": null, "changed": false }
								  ],
								  "unansweredCount": 1,
								  "createdAt": "2026-09-19T05:31:00Z", "updatedAt": "2026-09-19T05:31:00Z"
								}
								""")
					})))
	@PostMapping("/fixes")
	@ResponseStatus(HttpStatus.CREATED)
	public ReadinessFixResponse createFix(
			@PathVariable UUID handoverId,
			@Valid @RequestBody CreateFixRequest request,
			Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		return readinessFixService.create(handoverId, request.areas());
	}

	@Operation(summary = "보완안 조회",
			description = """
					보완안의 상태·영역별 결과·질문·섹션별 수정 전후를 반환한다. 인계자만 가능.
					stale=true면 보완을 시작한 뒤 문서가 바뀐 것이라 적용할 수 없다 → 다시 평가한 뒤 새로 시작한다.
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

	@Operation(summary = "보완 질문 답변 저장",
			description = """
					보완 질문에 답을 저장한다. **AI를 부르지 않고 사용량도 차감하지 않는다.** 인계자만 가능.
					답한 질문만 보내면 되고, 여러 번 나눠 보내도 된다(같은 질문은 마지막 답으로 바뀐다).
					수정안은 POST /fixes/{fixId}/generate에서 한 번에 만든다. 이미 수정안이 있어도 답을 고친 뒤 다시 만들 수 있다.

					- 없는 질문 id: 400(code=BAD_REQUEST)
					- 이미 적용·취소한 보완안: 409(code=READINESS_FIX_INVALID_STATE)
					- 그사이 문서가 바뀜: 409(code=AI_DRAFT_REVISION_CONFLICT)
					""",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = FixAnswerRequest.class),
					examples = {
						@ExampleObject(name = "기본", value = """
								{ "answers": [ { "questionId": "q1", "answer": "체크리스트.xlsx: 마케팅 확인 후 팀장 승인" } ] }
								""")
					})))
	@PutMapping("/fixes/{fixId}/answers")
	public ReadinessFixResponse answerFix(
			@PathVariable UUID handoverId,
			@PathVariable UUID fixId,
			@Valid @RequestBody FixAnswerRequest request,
			Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		return readinessFixService.answer(handoverId, fixId, request);
	}

	@Operation(summary = "보완안 만들기",
			description = """
					모든 영역의 수정안을 한 번에 만든다(동기, AI 호출 1회 · **AI 사용량 1회 차감**). 인계자만 가능. **문서는 바뀌지 않는다.**
					업로드 자료와 저장된 답변을 근거로, 영역마다 평가의 해결 방법이 가리킨 섹션(areas[].sections)을 고친다.

					- 수정안이 나온 영역: areas[].proposed=true, changeSummary·evidence. sections[]의 before/after로 비교한다.
					- 아직 부족한 영역: proposed=false, 새 질문이 붙는다(답하고 다시 만들면 된다).
					- 충돌(CONFLICT) 영역은 "어느 쪽이 맞나요?"에 답해야 수정안이 나온다(AI가 자료 중 하나를 고르지 않는다).
					  답한 값은 확인된 업무 기준에 기록되고, 다음 평가는 이 값을 확정값으로 본다.
					- 수정안이 있는 영역이 하나라도 있으면 status=PROPOSED(적용 가능), 없으면 NEEDS_INPUT.

					- 이미 적용·취소한 보완안: 409(code=READINESS_FIX_INVALID_STATE)
					- 그사이 문서가 바뀜: 409(code=AI_DRAFT_REVISION_CONFLICT)
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
			responses = @ApiResponse(responseCode = "200", description = "성공", content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = ReadinessFixResponse.class),
					examples = {
						@ExampleObject(name = "일부 영역만 수정안이 나온 경우(PROPOSED)", value = """
								{
								  "fixId": "c9d8e7f6-...",
								  "status": "PROPOSED",
								  "baseRevision": 4, "stale": false, "appliedRevision": null,
								  "areas": [
								    {
								      "area": "EXCEPTION", "areaLabel": "예외 대응", "status": "CONFLICT", "statusLabel": "충돌",
								      "sections": [
								        { "section": "RULES_AND_EXCEPTIONS", "field": "rulesAndExceptions", "label": "업무 기준과 예외" },
								        { "section": "CONFIRMED_CRITERIA", "field": "confirmedCriteria", "label": "확인된 업무 기준" }
								      ],
								      "proposed": true, "changeSummary": "환불 승인 기준을 마케팅 확인 후 팀장 승인으로 확정해 적었어요",
								      "evidence": [ { "sourceId": "a1b2c3d4-...", "fileName": "체크리스트.xlsx", "locator": "청크 3/8" } ],
								      "questions": [
								        { "id": "q1", "area": "EXCEPTION", "question": "환불 승인은 누가 하나요?", "reason": "자료마다 승인자가 달라요",
								          "options": ["운영 매뉴얼.pdf: 팀장 승인", "체크리스트.xlsx: 마케팅 확인 후 팀장 승인"],
								          "clarificationQuestionId": null, "answer": "체크리스트.xlsx: 마케팅 확인 후 팀장 승인" }
								      ]
								    },
								    {
								      "area": "ACCESS", "areaLabel": "접근 권한", "status": "MISSING", "statusLabel": "누락",
								      "sections": [ { "section": "ACCESS_ACCOUNTS", "field": "accessAccounts", "label": "접근 권한과 계정" } ],
								      "proposed": false, "changeSummary": null, "evidence": [],
								      "questions": [
								        { "id": "q2", "area": "ACCESS", "question": "운영툴 관리자 권한은 누가 발급하나요?", "reason": "자료에 발급 절차가 없어요",
								          "options": [], "clarificationQuestionId": null, "answer": null }
								      ]
								    }
								  ],
								  "sections": [
								    { "section": "RULES_AND_EXCEPTIONS", "field": "rulesAndExceptions", "label": "업무 기준과 예외",
								      "before": ["결제 오류 시 고객센터와 협업해 환불한다."],
								      "after": ["결제 오류 시 고객센터와 협업해 환불한다.", "환불은 마케팅 확인 후 팀장이 승인한다."], "changed": true },
								    { "section": "CONFIRMED_CRITERIA", "field": "confirmedCriteria", "label": "확인된 업무 기준",
								      "before": [], "after": [ { "label": "환불 승인", "value": "마케팅 확인 후 팀장 승인" } ], "changed": true },
								    { "section": "ACCESS_ACCOUNTS", "field": "accessAccounts", "label": "접근 권한과 계정",
								      "before": [], "after": [], "changed": false }
								  ],
								  "unansweredCount": 1,
								  "createdAt": "2026-09-19T05:31:00Z", "updatedAt": "2026-09-19T05:33:00Z"
								}
								""")
					})))
	@PostMapping("/fixes/{fixId}/generate")
	public ReadinessFixResponse generateFix(
			@PathVariable UUID handoverId,
			@PathVariable UUID fixId,
			Authentication authentication) {
		UUID userId = handoverAccess.requireOwner(handoverId, authentication);
		return aiTaskLockService.runExclusive(AiTask.READINESS_FIX, handoverId, () -> readinessFixService.generate(
				handoverId, fixId, () -> aiUsageGuard.acquire(userId, AiFeature.READINESS_FIX, handoverId)));
	}

	@Operation(summary = "보완안 적용",
			description = """
					사용자가 확인한 수정안(PROPOSED)을 문서에 적용한다. 인계자만 가능.
					수정안이 있는 영역(areas[].proposed=true)의 섹션만 바꾸고 나머지 섹션은 그대로 둔다.
					적용 뒤 **바뀐 섹션이 걸린 영역만** 다시 평가하고, 나머지 영역은 직전 평가 결과를 그대로 쓴다.
					"나중에 답하기"로 미룬 확인 질문에 여기서 답했다면 그 질문도 답변·반영 완료로 바뀐다.
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
