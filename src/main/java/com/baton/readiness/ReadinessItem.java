package com.baton.readiness;

import java.util.List;

import com.baton.ai.DraftSection;

/**
 * 영역 하나의 평가 결과(평가 행의 JSON 컬럼에 저장). 점수는 저장하지 않고 평가 기준 버전으로 매번 계산한다.
 *
 * @param section        "문서에서 수정하기" 이동 위치. targetSections의 첫 번째.
 * @param anchorText     문서 안에서 강조할 문장. 섹션 안에 실제로 있는 문장만 남기고, 없으면 null.
 * @param summary        지금 상태의 설명. 예: "환불 오류 대응 담당자가 명확하지 않아요"
 * @param resolution     해결 방법. 예: "환불 오류 시 처리 절차와 담당자를 확인해 추가하세요"
 * @param targetSections 보완안이 고칠 문서 섹션(해결 방법이 가리키는 곳). v1 평가에는 없다(null).
 * @param questions      보완할 때 인계자에게 물을 질문. v1 평가에는 없다(null).
 */
public record ReadinessItem(
		ReadinessArea area,
		ReadinessStatus status,
		DraftSection section,
		String anchorText,
		String summary,
		String resolution,
		List<ReadinessEvidence> evidence,
		List<DraftSection> targetSections,
		List<ItemQuestion> questions) {

	/** 보완안이 고칠 섹션. 옛 평가(targetSections 없음)는 section 하나. is/get 접두어를 쓰지 않는다(JSON 속성이 되지 않게). */
	public List<DraftSection> fixSections() {
		return targetSections == null || targetSections.isEmpty() ? List.of(section) : targetSections;
	}

	public List<ItemQuestion> questionList() {
		return questions == null ? List.of() : questions;
	}
}
