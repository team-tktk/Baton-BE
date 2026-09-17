package com.baton.aiusage;

/** 하루 구간 안의 요청 수를 기간별로 나눠 센 결과. 요청이 하나도 없으면 JPQL sum이 null이라 0으로 본다. */
public record AiUsageCounts(Long lastMinute, Long lastHour, Long lastDay) {

	public long get(AiUsageWindow window) {
		Long value = switch (window) {
			case MINUTE -> lastMinute;
			case HOUR -> lastHour;
			case DAY -> lastDay;
		};
		return value == null ? 0 : value;
	}
}
