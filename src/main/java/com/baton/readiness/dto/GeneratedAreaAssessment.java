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
		@JsonPropertyDescription("해결 방법대로 고칠 문서 섹션 1~2개. 이 영역의 섹션 목록 중에서만, resolution이 가리키는 곳과 똑같이 고르세요")
		List<DraftSection> targetSections,
		@JsonPropertyDescription("문서에서 문제가 되는 문장을 초안 JSON의 값 그대로 복사. 해당 문장이 없으면 빈 문자열")
		String anchorText,
		@JsonPropertyDescription("현재 상태 설명 한 문장, 40자 안팎. 예: 환불 오류 대응 담당자가 명확하지 않아요")
		String summary,
		@JsonPropertyDescription("사용자가 할 일 한두 문장. 섹션은 화면 이름으로. 예: 업무 기준과 예외에 환불 오류 시 처리 절차와 담당자를 적어주세요")
		String resolution,
		@JsonPropertyDescription("판단 근거가 된 업로드 파일. 파일명은 자료 목록의 이름 그대로")
		List<GeneratedEvidence> evidence,
		@JsonPropertyDescription("자료에 답이 없어 인계자에게 물어야 할 질문 0~3개. SUFFICIENT면 빈 배열")
		List<GeneratedItemQuestion> questions) {
}
