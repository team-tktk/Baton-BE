package com.baton.aiusage.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.baton.aiusage.AiTask;
import com.baton.aiusage.AiUsageLimitExceededException;
import com.baton.aiusage.AiUsageScope;
import com.baton.aiusage.AiUsageWindow;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 버튼 옆에 보여줄 사용 가능 여부·처리 중 상태")
public record AiUsageStatusResponse(
		@Schema(description = "지금 AI 요청(인수인계서 생성·보완안 생성·채팅)을 보낼 수 있는지")
		boolean available,
		@Schema(description = "available=false일 때 다시 사용할 수 있는 시각(UTC). 가능하면 null")
		Instant retryAt,
		@Schema(description = "retryAt까지 남은 초. 가능하면 null")
		Long retryAfterSeconds,
		@Schema(description = "한도별 사용량(세 기능 합산). 한도 기능이 꺼져 있으면 빈 배열")
		List<Usage> usages,
		@Schema(description = "handoverId를 넘겼을 때, 그 인수인계에서 지금 처리 중인 AI 작업. 없으면 빈 배열")
		List<Running> running) {

	public static AiUsageStatusResponse of(List<UsageItem> items, List<AiTask> running, Instant now) {
		Instant retryAt = items.stream()
				.filter(UsageItem::blocked)
				.map(UsageItem::retryAt)
				.max(Comparator.naturalOrder())
				.orElse(null);
		return new AiUsageStatusResponse(
				retryAt == null,
				retryAt,
				retryAt == null ? null : AiUsageLimitExceededException.retryAfterSeconds(now, retryAt),
				items.stream().map(Usage::from).toList(),
				running.stream().map(Running::from).toList());
	}

	/** 한 scope·기간의 사용량(내부 계산용). retryAt이 있으면 한도에 걸린 상태. */
	public record UsageItem(AiUsageScope scope, AiUsageWindow window, int limit, long used, Instant retryAt) {

		public boolean blocked() {
			return retryAt != null;
		}
	}

	public record Usage(
			@Schema(description = "USER(내 계정) · ORGANIZATION(내 팀 전체, 기본 꺼짐)") AiUsageScope scope,
			String scopeLabel,
			@Schema(description = "MINUTE · HOUR · DAY (지금부터 거슬러 올라간 기간)") AiUsageWindow window,
			String windowLabel,
			int limit,
			long used,
			long remaining,
			@Schema(description = "이 한도에 걸렸을 때 다시 가능한 시각. 아니면 null") Instant retryAt) {

		static Usage from(UsageItem item) {
			return new Usage(item.scope(), item.scope().getLabel(), item.window(), item.window().getLabel(),
					item.limit(), item.used(), Math.max(0, item.limit() - item.used()), item.retryAt());
		}
	}

	public record Running(
			@Schema(description = "ANALYSIS · DRAFT_GENERATION · READINESS_EVALUATION · READINESS_FIX") AiTask task,
			String label) {

		static Running from(AiTask task) {
			return new Running(task, task.getLabel());
		}
	}
}
