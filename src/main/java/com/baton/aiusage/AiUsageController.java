package com.baton.aiusage;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baton.ai.AnalysisJobRepository;
import com.baton.ai.AnalysisJobStatus;
import com.baton.aiusage.dto.AiUsageStatusResponse;
import com.baton.auth.AuthService;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverPermission;
import com.baton.handover.HandoverRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "07. AI 사용량",
		description = "인수인계서 생성·보완안 생성·채팅을 합산한 분/시간/하루 요청 한도(비정상 연속 호출 차단용)와, 같은 인수인계 AI 작업의 중복 실행을 관리한다. "
				+ "한도를 넘으면 해당 AI API가 429(code=AI_USAGE_LIMIT_EXCEEDED)와 Retry-After 헤더, retryAt·retryAfterSeconds를 준다. "
				+ "같은 인수인계에서 같은 AI 작업이 이미 실행 중이면 409(code=AI_TASK_ALREADY_RUNNING).")
@RestController
@RequestMapping("/api/v1/ai-usage")
@RequiredArgsConstructor
public class AiUsageController {

	private final AiUsageGuard aiUsageGuard;
	private final AiTaskLockService aiTaskLockService;
	private final AnalysisJobRepository analysisJobRepository;
	private final HandoverRepository handoverRepository;
	private final HandoverPermission handoverPermission;
	private final AuthService authService;
	private final Clock clock;

	@Operation(summary = "AI 사용 가능 여부 조회",
			description = """
					AI 버튼(인수인계서 생성·보완안 생성·채팅)을 누르기 전에 지금 요청할 수 있는지, 막혔다면 언제 다시 가능한지 확인한다.
					사용량을 차감하지 않는다. handoverId를 주면 그 인수인계에서 지금 처리 중인 AI 작업(running)도 함께 준다(참여자만 가능).

					| 필드 | 설명 |
					| --- | --- |
					| available | false면 AI 버튼을 비활성화하고 retryAt을 안내 |
					| retryAt / retryAfterSeconds | 다시 사용할 수 있는 시각 / 남은 초 |
					| usages | 한도별 사용량(scope=USER 내 계정, ORGANIZATION 내 팀 전체 — 조직 한도는 기본 꺼짐) |
					| running | 처리 중인 작업(ANALYSIS · DRAFT_GENERATION · READINESS_EVALUATION · READINESS_FIX). 해당 버튼에 "처리 중" 표시 |

					한도는 인수인계서 생성·보완안 생성·채팅 요청을 **합산**해서 센다. 기간은 "지금부터 거슬러 올라간 1분/1시간/하루"다.
					한도값은 운영 중에 바뀔 수 있으니 프론트에 고정하지 말고 이 응답의 limit을 쓴다.
					- 없는 인수인계: 404(code=HANDOVER_NOT_FOUND) / 참여자 아님: 403(code=HANDOVER_FORBIDDEN)
					""",
			responses = @ApiResponse(responseCode = "200", description = "성공", content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = AiUsageStatusResponse.class),
					examples = {
						@ExampleObject(name = "1분 한도에 걸린 경우", value = """
								{
								  "available": false,
								  "retryAt": "2026-09-17T05:31:12Z",
								  "retryAfterSeconds": 34,
								  "usages": [
								    { "scope": "USER", "scopeLabel": "사용자", "window": "MINUTE", "windowLabel": "1분", "limit": 10, "used": 10, "remaining": 0, "retryAt": "2026-09-17T05:31:12Z" },
								    { "scope": "USER", "scopeLabel": "사용자", "window": "HOUR", "windowLabel": "1시간", "limit": 100, "used": 23, "remaining": 77, "retryAt": null },
								    { "scope": "USER", "scopeLabel": "사용자", "window": "DAY", "windowLabel": "하루", "limit": 200, "used": 41, "remaining": 159, "retryAt": null }
								  ],
								  "running": [ { "task": "READINESS_EVALUATION", "label": "준비도 평가" } ]
								}
								""")
					})))
	@GetMapping
	@Transactional(readOnly = true)
	public AiUsageStatusResponse status(
			@Parameter(description = "처리 중 상태를 함께 볼 인수인계 id. 생략 가능.")
			@RequestParam(required = false) UUID handoverId,
			Authentication authentication) {
		UUID userId = authService.getByEmail(authentication.getName()).getId();

		List<AiTask> running = new ArrayList<>();
		if (handoverId != null) {
			Handover handover = handoverRepository.findById(handoverId)
					.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
			handoverPermission.requireViewer(handover, userId);
			// 분석은 비동기 job이라 잠금이 아니라 job 상태로 처리 중을 판단한다.
			if (analysisJobRepository.existsByHandoverIdAndStatusIn(handoverId, AnalysisJobStatus.active())) {
				running.add(AiTask.ANALYSIS);
			}
			running.addAll(aiTaskLockService.runningTasks(handoverId));
		}
		return AiUsageStatusResponse.of(aiUsageGuard.currentUsage(userId), running, clock.instant());
	}
}
