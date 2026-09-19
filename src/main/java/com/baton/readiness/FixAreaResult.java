package com.baton.readiness;

import java.util.List;

import com.baton.ai.DraftSection;

/**
 * 보완안 안의 영역 하나. 보완을 시작할 때 평가 결과(상태·고칠 섹션)를 옮겨 두고, 보완안을 만들 때마다 결과를 채운다.
 *
 * @param status        보완을 시작할 때의 평가 상태. 충돌이면 인계자 답변 없이는 수정안을 내지 않는다.
 * @param sections      이 영역이 고칠 문서 섹션.
 * @param proposed      이 영역의 수정안이 있는가. 적용하면 이 영역의 섹션만 문서에 반영된다.
 * @param changeSummary 무엇을 바꿨는지 한 문장. 수정안이 없으면 null.
 * @param evidence      수정안의 근거 파일.
 */
public record FixAreaResult(
		ReadinessArea area,
		ReadinessStatus status,
		List<DraftSection> sections,
		boolean proposed,
		String changeSummary,
		List<ReadinessEvidence> evidence) {

	static FixAreaResult open(ReadinessItem item) {
		return new FixAreaResult(item.area(), item.status(), item.fixSections(), false, null, List.of());
	}

	FixAreaResult propose(String changeSummary, List<ReadinessEvidence> evidence) {
		return new FixAreaResult(area, status, sections, true, changeSummary, List.copyOf(evidence));
	}

	FixAreaResult unresolved() {
		return new FixAreaResult(area, status, sections, false, null, List.of());
	}
}
