package com.baton.readiness.dto;

import java.util.List;

import com.baton.ai.dto.HandoverDraftContent;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** AI가 부족 항목 하나에 대해 만든 보완 결과(모델 출력 형식). */
public record GeneratedFix(
		@JsonPropertyDescription("자료 발췌나 인계자 답변만으로 부족한 내용을 채울 수 있으면 true, 사실이 부족하면 false")
		boolean resolvable,
		@JsonPropertyDescription("resolvable=true일 때 수정 대상 섹션 필드 하나만 '수정 후 전체 값'으로 채우고, 나머지 필드는 전부 null")
		HandoverDraftContent patch,
		@JsonPropertyDescription("무엇을 바꿨는지 한 문장. resolvable=false면 빈 문자열")
		String changeSummary,
		@JsonPropertyDescription("수정안의 근거로 쓴 자료 발췌 번호 목록. 예: [1, 3]")
		List<Integer> excerptNumbers,
		@JsonPropertyDescription("resolvable=false일 때 인계자에게 물을 질문 1~3개. resolvable=true면 빈 배열")
		List<GeneratedFixQuestion> questions) {
}
