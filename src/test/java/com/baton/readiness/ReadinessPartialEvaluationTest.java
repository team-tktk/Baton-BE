package com.baton.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baton.ai.DraftSection;

/** 보완 적용 뒤에는 바뀐 섹션이 걸린 영역만 다시 평가하고, 평가 프롬프트에는 섹션을 화면 이름으로 넘긴다. */
class ReadinessPartialEvaluationTest {

	private final ReadinessRubric rubric = ReadinessRubrics.CURRENT;

	@Test
	void keepsBaseItemsForAreasNotReevaluated() {
		ReadinessEvaluation base = evaluation(ReadinessStatus.PARTIAL);
		List<ReadinessItem> fresh = items(ReadinessStatus.SUFFICIENT);

		List<ReadinessItem> merged = ReadinessService.mergeItems(base, fresh,
				EnumSet.of(ReadinessArea.EXCEPTION, ReadinessArea.PROCEDURE));

		assertThat(merged).extracting(ReadinessItem::area).containsExactly(ReadinessArea.values());
		assertThat(merged).filteredOn(item -> item.area() == ReadinessArea.EXCEPTION || item.area() == ReadinessArea.PROCEDURE)
				.allSatisfy(item -> assertThat(item.status()).isEqualTo(ReadinessStatus.SUFFICIENT));
		assertThat(merged).filteredOn(item -> item.area() != ReadinessArea.EXCEPTION && item.area() != ReadinessArea.PROCEDURE)
				.allSatisfy(item -> assertThat(item.status()).isEqualTo(ReadinessStatus.PARTIAL));
		// 예외 대응(15) + 실행 절차(20)만 충분, 나머지 65점은 일부 부족(50%) → 35 + 32.5 = 67.5 → 68
		assertThat(ReadinessEvaluation.create(UUID.randomUUID(), rubric, "h", 2, merged).getScore()).isEqualTo(68);
	}

	@Test
	void criteriaListOnlyRequestedAreasWithSectionLabels() {
		String criteria = ReadinessService.describeCriteria(rubric, EnumSet.of(ReadinessArea.EXCEPTION));

		assertThat(criteria).startsWith("- EXCEPTION / 예외 대응 / ");
		assertThat(criteria).contains("업무 기준과 예외(RULES_AND_EXCEPTIONS)", "확인된 업무 기준(CONFIRMED_CRITERIA)");
		assertThat(criteria).doesNotContain("PROCEDURE");
	}

	@Test
	void v2KeepsV1Scoring() {
		assertThat(ReadinessRubrics.CURRENT.version()).isEqualTo("v2");
		assertThat(ReadinessRubrics.V2.weights()).isEqualTo(ReadinessRubrics.V1.weights());
		assertThat(ReadinessRubrics.V2.statusPercent()).isEqualTo(ReadinessRubrics.V1.statusPercent());
		assertThat(ReadinessRubrics.V2.readyScore()).isEqualTo(80);
		assertThat(ReadinessRubrics.V2.minimumScore()).isEqualTo(50);
		assertThat(ReadinessRubrics.find("v1")).isPresent();
	}

	@Test
	void plainTextReplacesOnlyStandaloneCodes() {
		assertThat(ReadinessText.plain(" RULES_AND_EXCEPTIONS(rulesAndExceptions)을 보세요 "))
				.isEqualTo("업무 기준과 예외(업무 기준과 예외)을 보세요");
		assertThat(ReadinessText.plain("SCHEDULE 확인")).isEqualTo("업무 일정 확인");
		assertThat(ReadinessText.plain("MY_RULES_AND_EXCEPTIONS_V2")).isEqualTo("MY_RULES_AND_EXCEPTIONS_V2");
		assertThat(ReadinessText.plain("  ")).isNull();
	}

	private ReadinessEvaluation evaluation(ReadinessStatus status) {
		return ReadinessEvaluation.create(UUID.randomUUID(), rubric, "base", 1, items(status));
	}

	private static List<ReadinessItem> items(ReadinessStatus status) {
		return Arrays.stream(ReadinessArea.values())
				.map(area -> new ReadinessItem(area, status, area.primarySection(), null, "요약", null, List.of(),
						List.of(area.primarySection()), List.of()))
				.toList();
	}
}
