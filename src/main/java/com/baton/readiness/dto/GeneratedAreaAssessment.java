package com.baton.readiness.dto;

import java.util.List;

import com.baton.ai.DraftSection;
import com.baton.readiness.ReadinessArea;
import com.baton.readiness.ReadinessStatus;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** AI가 영역 하나를 평가한 결과(모델 출력 형식). 서버가 ReadinessItemNormalizer로 검증한 뒤 저장한다. */
public record GeneratedAreaAssessment(
		@JsonPropertyDescription("평가 영역")
		ReadinessArea area,
		@JsonPropertyDescription("SUFFICIENT(충분)·PARTIAL(일부 부족)·MISSING(누락)·CONFLICT(충돌) 중 하나")
		ReadinessStatus status,
		@JsonPropertyDescription("부족한 내용이 들어가야 할 문서 섹션. 영역별로 허용된 섹션 중에서만 고르세요")
		DraftSection section,
		@JsonPropertyDescription("문서에서 문제가 되는 문장을 초안 JSON의 값 그대로 복사. 해당 문장이 없으면 빈 문자열")
		String anchorText,
		@JsonPropertyDescription("현재 상태 설명 한 문장, 40자 안팎. 예: 환불 오류 대응 담당자가 명확하지 않아요")
		String summary,
		@JsonPropertyDescription("사용자가 할 일 한두 문장. 예: 환불 오류 시 처리 절차와 담당자를 확인해 문서에 적어주세요")
		String resolution,
		@JsonPropertyDescription("판단 근거가 된 업로드 파일. 파일명은 자료 목록의 이름 그대로")
		List<GeneratedEvidence> evidence) {
}
