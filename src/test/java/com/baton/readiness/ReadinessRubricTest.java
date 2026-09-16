package com.baton.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ReadinessRubricTest {

	private final ReadinessRubric rubric = ReadinessRubrics.V1;

	@Test
	void allSufficientIsFullScoreAndAllMissingIsZero() {
		assertThat(rubric.score(all(ReadinessStatus.SUFFICIENT))).isEqualTo(100);
		assertThat(rubric.score(all(ReadinessStatus.MISSING))).isZero();
		assertThat(rubric.grade(100)).isEqualTo(ReadinessGrade.READY);
		assertThat(rubric.grade(0)).isEqualTo(ReadinessGrade.NOT_READY);
	}

	@Test
	void computesWeightedScoreWithSingleRounding() {
		Map<ReadinessArea, ReadinessStatus> statuses = all(ReadinessStatus.SUFFICIENT);
		statuses.put(ReadinessArea.EXCEPTION, ReadinessStatus.MISSING);   // -15
		statuses.put(ReadinessArea.CONTACTS, ReadinessStatus.PARTIAL);    // -5
		statuses.put(ReadinessArea.PROCEDURE, ReadinessStatus.PARTIAL);   // -10
		statuses.put(ReadinessArea.SCOPE, ReadinessStatus.CONFLICT);      // -11.25

		// 100 - 15 - 5 - 10 - 11.25 = 58.75 → 59
		assertThat(rubric.score(statuses)).isEqualTo(59);
		assertThat(rubric.grade(59)).isEqualTo(ReadinessGrade.NEEDS_IMPROVEMENT);
	}

	@Test
	void sameStatusesAlwaysGiveSameScore() {
		Map<ReadinessArea, ReadinessStatus> statuses = all(ReadinessStatus.PARTIAL);
		statuses.put(ReadinessArea.ACCESS, ReadinessStatus.CONFLICT);

		assertThat(rubric.score(statuses)).isEqualTo(rubric.score(new HashMap<>(statuses)));
	}

	@Test
	void keyIssuesAreBiggestLossesFirstAndPotentialScoreResolvesThem() {
		Map<ReadinessArea, ReadinessStatus> statuses = all(ReadinessStatus.SUFFICIENT);
		statuses.put(ReadinessArea.EXCEPTION, ReadinessStatus.MISSING);   // 1500
		statuses.put(ReadinessArea.PROCEDURE, ReadinessStatus.PARTIAL);   // 1000
		statuses.put(ReadinessArea.ACCESS, ReadinessStatus.MISSING);      // 1000 (영역 순서상 PROCEDURE 뒤)
		statuses.put(ReadinessArea.CONTACTS, ReadinessStatus.PARTIAL);    // 500

		assertThat(rubric.keyIssues(statuses))
				.containsExactly(ReadinessArea.EXCEPTION, ReadinessArea.PROCEDURE, ReadinessArea.ACCESS);
		assertThat(rubric.score(statuses)).isEqualTo(60);
		assertThat(rubric.potentialScore(statuses)).isEqualTo(95);
		assertThat(rubric.prioritized(statuses).get(0)).isEqualTo(ReadinessArea.EXCEPTION);
	}

	@Test
	void missingAreaCountsAsMissing() {
		Map<ReadinessArea, ReadinessStatus> statuses = all(ReadinessStatus.SUFFICIENT);
		statuses.remove(ReadinessArea.SCOPE);

		assertThat(rubric.score(statuses)).isEqualTo(85);
	}

	@Test
	void rejectsRubricWhoseWeightsDoNotSumTo100() {
		Map<ReadinessArea, Integer> weights = new EnumMap<>(rubric.weights());
		weights.put(ReadinessArea.SCOPE, 30);

		assertThatThrownBy(() -> new ReadinessRubric("bad", rubric.criteria(), weights, rubric.statusPercent(), 80, 50, 3))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void findsRubricByVersion() {
		assertThat(ReadinessRubrics.find("v1")).contains(ReadinessRubrics.V1);
		assertThat(ReadinessRubrics.find("v0")).isEmpty();
	}

	private static Map<ReadinessArea, ReadinessStatus> all(ReadinessStatus status) {
		Map<ReadinessArea, ReadinessStatus> statuses = new EnumMap<>(ReadinessArea.class);
		for (ReadinessArea area : ReadinessArea.values()) {
			statuses.put(area, status);
		}
		return statuses;
	}
}
