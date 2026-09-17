package com.baton.aiusage;

import java.time.Duration;

/** 한도를 세는 기간. 고정 구간이 아니라 "지금부터 거슬러 올라간 기간"(슬라이딩 윈도우)으로 센다. */
public enum AiUsageWindow {
	MINUTE("1분", Duration.ofMinutes(1)),
	HOUR("1시간", Duration.ofHours(1)),
	DAY("하루", Duration.ofDays(1));

	private final String label;
	private final Duration duration;

	AiUsageWindow(String label, Duration duration) {
		this.label = label;
		this.duration = duration;
	}

	public String getLabel() {
		return label;
	}

	public Duration getDuration() {
		return duration;
	}
}
