package com.baton.aiusage;

/** 한 scope에 실제로 적용할 한도. 기간별 값이 null이면 그 기간은 제한하지 않는다. */
public record AiUsageLimits(boolean enabled, Integer perMinute, Integer perHour, Integer perDay) {

	public static final AiUsageLimits DISABLED = new AiUsageLimits(false, null, null, null);

	public AiUsageLimits {
		perMinute = positiveOrNull(perMinute);
		perHour = positiveOrNull(perHour);
		perDay = positiveOrNull(perDay);
	}

	public Integer limitFor(AiUsageWindow window) {
		return switch (window) {
			case MINUTE -> perMinute;
			case HOUR -> perHour;
			case DAY -> perDay;
		};
	}

	/** 0 이하는 설정 실수로 보고 "제한 없음"으로 취급한다(모든 요청이 영구 차단되는 사고 방지). */
	private static Integer positiveOrNull(Integer value) {
		return value == null || value < 1 ? null : value;
	}
}
