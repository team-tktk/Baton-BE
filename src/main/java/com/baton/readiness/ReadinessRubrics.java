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

	/**
	 * v2: 점수 계산(확인 내용·배점·상태 비율·등급 경계)은 v1과 같다. 평가 방식만 바뀌었다 —
	 * 확인된 업무 기준에 사람이 확정한 값이 있으면 자료끼리 달라도 충돌로 보지 않고,
	 * 영역마다 고칠 섹션(해결 방법과 같은 곳)과 인계자에게 물을 질문을 함께 낸다.
	 */
	public static final ReadinessRubric V2 = new ReadinessRubric(
			"v2", V1.criteria(), V1.weights(), V1.statusPercent(), V1.readyScore(), V1.minimumScore(), V1.keyIssueCount());

	/**
	 * v3: 접근 권한·근거와 최신성 영역을 뺐다. 인계자가 문서만으로 채우기 어렵고(계정·권한은 보안상 적지 않는 경우가 많고,
	 * 자료의 최신성은 문서를 고쳐서 올릴 수 있는 점수가 아니다) 점수만 깎는 항목이었다. 남은 여섯 영역에 20점을 나눠 실었다.
	 * 자료끼리 어긋나는 값은 각 영역의 CONFLICT(충돌) 상태로 계속 잡는다. 상태 비율·등급 경계·평가 방식은 v2와 같다.
	 */
	public static final ReadinessRubric V3 = new ReadinessRubric(
			"v3",
			Map.of(
					ReadinessArea.SCOPE, V1.criteria().get(ReadinessArea.SCOPE),
					ReadinessArea.PROCEDURE, V1.criteria().get(ReadinessArea.PROCEDURE),
					ReadinessArea.COMPLETION, V1.criteria().get(ReadinessArea.COMPLETION),
					ReadinessArea.EXCEPTION, V1.criteria().get(ReadinessArea.EXCEPTION),
					ReadinessArea.SCHEDULE, V1.criteria().get(ReadinessArea.SCHEDULE),
					ReadinessArea.CONTACTS, V1.criteria().get(ReadinessArea.CONTACTS)),
			Map.of(
					ReadinessArea.SCOPE, 15,
					ReadinessArea.PROCEDURE, 25,
					ReadinessArea.COMPLETION, 15,
					ReadinessArea.EXCEPTION, 15,
					ReadinessArea.SCHEDULE, 15,
					ReadinessArea.CONTACTS, 15),
			V1.statusPercent(), V1.readyScore(), V1.minimumScore(), V1.keyIssueCount());

	/**
	 * v4: 진행 현황·우선순위 영역을 더했다. 둘 다 인계자만 알지만 질문 한두 개로 바로 채울 수 있고, 후임자가 가장 먼저 되묻는 내용이다.
	 * - 진행 현황: 절차는 잘 적혀 있어도 "지금 어디까지 했는지"가 없으면 이어받을 수 없다. 진행 중 업무가 없으면 반복 업무로 판단한다.
	 * - 우선순위: 업무 하나하나가 아니라 업무들 사이의 경중. 기존 영역 어디에서도 보지 않던 것.
	 * 담당자에는 인계 후 전임자에게 물어볼 수 있는 기간·방법을 더했다. 상태 비율·등급 경계·평가 방식은 v3과 같다.
	 */
	public static final ReadinessRubric V4 = new ReadinessRubric(
			"v4",
			Map.of(
					ReadinessArea.SCOPE, V1.criteria().get(ReadinessArea.SCOPE),
					ReadinessArea.PROCEDURE, V1.criteria().get(ReadinessArea.PROCEDURE),
					ReadinessArea.PROGRESS, "진행 중인 업무를 바로 이어받을 수 있는가 (업무별로 지금 어디까지 했는지, 다음에 할 일, 기다리고 있는 승인·회신)."
							+ " 진행 중 업무가 없고 반복 업무만 있으면 반복 업무의 현재 상태와 다음 할 일로 판단한다",
					ReadinessArea.PRIORITY, "무엇부터 챙겨야 하는지 알 수 있는가 (가장 중요하거나 밀리면 안 되는 업무, 바쁠 때 먼저 할 일과 미뤄도 되는 일)."
							+ " 업무가 하나뿐이면 그 업무에서 놓치면 안 되는 부분이 적혀 있는지로 판단한다",
					ReadinessArea.COMPLETION, V1.criteria().get(ReadinessArea.COMPLETION),
					ReadinessArea.EXCEPTION, V1.criteria().get(ReadinessArea.EXCEPTION),
					ReadinessArea.SCHEDULE, V1.criteria().get(ReadinessArea.SCHEDULE),
					ReadinessArea.CONTACTS, "문의·승인·보고 대상이 명확한가 (누구에게 묻고, 누가 승인하고, 누구에게 보고하는지,"
							+ " 인계 후 전임자에게 언제까지 어떤 방법으로 물어볼 수 있는지)"),
			Map.of(
					ReadinessArea.SCOPE, 10,
					ReadinessArea.PROCEDURE, 20,
					ReadinessArea.PROGRESS, 15,
					ReadinessArea.PRIORITY, 10,
					ReadinessArea.COMPLETION, 10,
					ReadinessArea.EXCEPTION, 15,
					ReadinessArea.SCHEDULE, 10,
					ReadinessArea.CONTACTS, 10),
			V1.statusPercent(), V1.readyScore(), V1.minimumScore(), V1.keyIssueCount());

	public static final ReadinessRubric CURRENT = V4;

	private static final Map<String, ReadinessRubric> BY_VERSION = Map.of(
			V1.version(), V1, V2.version(), V2, V3.version(), V3, V4.version(), V4);

	private ReadinessRubrics() {
	}

	public static Optional<ReadinessRubric> find(String version) {
		return Optional.ofNullable(BY_VERSION.get(version));
	}
}
