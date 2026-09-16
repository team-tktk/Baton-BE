package com.baton.readiness;

import java.util.Map;
import java.util.Optional;

/** 평가 기준 버전 목록. 기준을 바꿀 땐 기존 버전을 수정하지 말고 새 버전을 추가한 뒤 CURRENT를 옮긴다. */
public final class ReadinessRubrics {

	public static final ReadinessRubric V1 = new ReadinessRubric(
			"v1",
			Map.of(
					ReadinessArea.SCOPE, "어떤 업무를 넘기는지 명확한가 (업무 목적, 넘기는 업무 목록과 범위)",
					ReadinessArea.PROCEDURE, "후임자가 순서대로 따라 할 수 있는가 (업무별 구체적인 단계·다음 할 일)",
					ReadinessArea.COMPLETION, "업무가 끝났다고 판단할 수 있는가 (완료·인수 완료를 판단하는 기준)",
					ReadinessArea.EXCEPTION, "오류나 특수 상황의 대응 방법이 있는가 (예외 상황별 처리 절차와 판단 기준)",
					ReadinessArea.SCHEDULE, "반복 주기와 마감이 명확한가 (반복 업무의 주기, 진행 중 업무의 기한)",
					ReadinessArea.CONTACTS, "문의·승인·보고 대상이 명확한가 (누구에게 묻고, 누가 승인하고, 누구에게 보고하는지)",
					ReadinessArea.ACCESS, "필요한 시스템과 권한이 적혀 있는가 (시스템 이름, 필요한 권한 수준, 계정 상태)",
					ReadinessArea.EVIDENCE, "출처가 있고 최신 내용인가 (문서 내용이 업로드 자료에 근거하는지, 자료끼리 어긋나거나 오래된 내용은 없는지)"),
			Map.of(
					ReadinessArea.SCOPE, 15,
					ReadinessArea.PROCEDURE, 20,
					ReadinessArea.COMPLETION, 10,
					ReadinessArea.EXCEPTION, 15,
					ReadinessArea.SCHEDULE, 10,
					ReadinessArea.CONTACTS, 10,
					ReadinessArea.ACCESS, 10,
					ReadinessArea.EVIDENCE, 10),
			Map.of(
					ReadinessStatus.SUFFICIENT, 100,
					ReadinessStatus.PARTIAL, 50,
					ReadinessStatus.CONFLICT, 25,
					ReadinessStatus.MISSING, 0),
			80,
			50,
			3);

	public static final ReadinessRubric CURRENT = V1;

	private static final Map<String, ReadinessRubric> BY_VERSION = Map.of(V1.version(), V1);

	private ReadinessRubrics() {
	}

	public static Optional<ReadinessRubric> find(String version) {
		return Optional.ofNullable(BY_VERSION.get(version));
	}
}
