package com.baton.readiness;

import java.util.List;

import com.baton.ai.DraftSection;

/**
 * 영역 하나의 평가 결과(평가 행의 JSON 컬럼에 저장). 점수는 저장하지 않고 평가 기준 버전으로 매번 계산한다.
 *
 * @param section    부족한 내용이 있어야 할 문서 섹션(보완안 적용 위치·직접 수정 이동 위치).
 * @param anchorText 문서 안에서 강조할 문장. 섹션 안에 실제로 있는 문장만 남기고, 없으면 null.
 * @param summary    지금 상태의 설명. 예: "환불 오류 대응 담당자가 명확하지 않아요"
 * @param resolution 해결 방법. 예: "환불 오류 시 처리 절차와 담당자를 확인해 추가하세요"
 */
public record ReadinessItem(
		ReadinessArea area,
		ReadinessStatus status,
		DraftSection section,
		String anchorText,
		String summary,
		String resolution,
		List<ReadinessEvidence> evidence) {
}
