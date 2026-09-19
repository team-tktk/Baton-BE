package com.baton.readiness;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 준비도 평가 기준 한 버전. 영역별 확인 내용·배점, 상태별 환산 비율, 등급 경계를 한데 묶는다.
 * 기준이 바뀌면 이 값을 고치지 말고 새 버전을 ReadinessRubrics에 추가한다 — 평가 결과마다 버전이 저장되므로
 * 예전 결과를 예전 기준으로 다시 읽을 수 있고, 같은 내용·같은 버전이면 같은 점수가 나온다.
 *
 * @param weights       영역별 배점. 합계 100.
 * @param statusPercent 상태별로 배점의 몇 %를 인정할지(0~100).
 * @param readyScore    이 점수 이상이면 READY.
 * @param minimumScore  이 점수 이상이면 NEEDS_IMPROVEMENT, 미만이면 NOT_READY.
 * @param keyIssueCount "중요한 확인"으로 먼저 보여줄 부족 항목 수.
 */
public record ReadinessRubric(
		String version,
		Map<ReadinessArea, String> criteria,
		Map<ReadinessArea, Integer> weights,
		Map<ReadinessStatus, Integer> statusPercent,
		int readyScore,
		int minimumScore,
		int keyIssueCount) {

	public ReadinessRubric {
		criteria = Map.copyOf(criteria);
		weights = Map.copyOf(weights);
		statusPercent = Map.copyOf(statusPercent);
		for (ReadinessArea area : ReadinessArea.values()) {
			if (!criteria.containsKey(area) || !weights.containsKey(area)) {
				throw new IllegalArgumentException("평가 기준 " + version + "에 영역 " + area + "이(가) 빠졌습니다.");
			}
		}
		for (ReadinessStatus status : ReadinessStatus.values()) {
			if (!statusPercent.containsKey(status)) {
				throw new IllegalArgumentException("평가 기준 " + version + "에 상태 " + status + "의 비율이 빠졌습니다.");
			}
		}
		int total = weights.values().stream().mapToInt(Integer::intValue).sum();
		if (total != 100) {
			throw new IllegalArgumentException("평가 기준 " + version + "의 배점 합계가 100이 아닙니다: " + total);
		}
	}

	public int weight(ReadinessArea area) {
		return weights.get(area);
	}

	/** 영역 점수(0~100%). */
	public int areaPercent(ReadinessStatus status) {
		return statusPercent.get(status);
	}

	/** 이 영역에서 잃은 점수(1/100점 단위 정수). 정렬·우선순위용이라 반올림하지 않는다. */
	public int lostCentiPoints(ReadinessArea area, ReadinessStatus status) {
		return weight(area) * (100 - areaPercent(status));
	}

	/** 총점(0~100). 정수 연산 후 한 번만 반올림해 같은 입력이면 항상 같은 값이 나온다. */
	public int score(Map<ReadinessArea, ReadinessStatus> statuses) {
		long centiPoints = 0;
		for (ReadinessArea area : ReadinessArea.values()) {
			centiPoints += (long) weight(area) * areaPercent(statusOf(statuses, area));
		}
		return (int) ((centiPoints + 50) / 100);
	}

	/** 점수를 가장 많이 잃은 순서의 부족 영역(최대 keyIssueCount개). 동점이면 영역 정의 순서. */
	public List<ReadinessArea> keyIssues(Map<ReadinessArea, ReadinessStatus> statuses) {
		return prioritized(statuses).stream()
				.filter(area -> statusOf(statuses, area) != ReadinessStatus.SUFFICIENT)
				.limit(keyIssueCount)
				.toList();
	}

	/** 모든 영역을 잃은 점수가 큰 순서로. */
	public List<ReadinessArea> prioritized(Map<ReadinessArea, ReadinessStatus> statuses) {
		return List.of(ReadinessArea.values()).stream()
				.sorted(Comparator.comparingInt((ReadinessArea area) -> lostCentiPoints(area, statusOf(statuses, area)))
						.reversed()
						.thenComparing(Comparator.naturalOrder()))
				.toList();
	}

	public ReadinessGrade grade(int score) {
		if (score >= readyScore) {
			return ReadinessGrade.READY;
		}
		return score >= minimumScore ? ReadinessGrade.NEEDS_IMPROVEMENT : ReadinessGrade.NOT_READY;
	}

	/** 결과에 없는 영역은 누락으로 본다. */
	private static ReadinessStatus statusOf(Map<ReadinessArea, ReadinessStatus> statuses, ReadinessArea area) {
		return statuses.getOrDefault(area, ReadinessStatus.MISSING);
	}
}
