package com.baton.readiness.dto;

import java.util.List;

import com.baton.readiness.ReadinessArea;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 보완할 영역 하나의 결과(모델 출력 형식). */
public record GeneratedAreaFix(
		@JsonPropertyDescription("보완할 영역")
		ReadinessArea area,
		@JsonPropertyDescription("자료 발췌나 인계자 답변으로 해결 방법을 채웠으면 true, 사실이 부족하면 false")
		boolean resolved,
		@JsonPropertyDescription("무엇을 바꿨는지 한 문장(섹션은 화면 이름으로). resolved=false면 빈 문자열")
		String changeSummary,
		@JsonPropertyDescription("이 영역의 근거로 쓴 자료 발췌 번호 목록. 예: [1, 3]")
		List<Integer> excerptNumbers,
		@JsonPropertyDescription("resolved=false일 때 인계자에게 물을 질문 1~3개. resolved=true면 빈 배열")
		List<GeneratedItemQuestion> questions) {
}
