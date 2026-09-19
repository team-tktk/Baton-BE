package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.baton.ai.dto.AccessItem;
import com.baton.ai.dto.ConfirmedCriterion;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.ai.dto.Stakeholder;

class DraftSectionTest {

	private static final HandoverDraftContent BASE = new HandoverDraftContent(
			"사람이 고친 목적", "완료 기준", List.of(), List.of(), List.of("기존 예외 규칙"),
			List.of(new Stakeholder("김미영", "마케팅팀", "쿠폰 정책")), List.of(), List.of(),
			List.of(new AccessItem("운영 어드민", "주문 조회", "사용 가능")), List.of("체크"), List.of());

	@Test
	void mergeReplacesOnlyTargetSection() {
		HandoverDraftContent patch = new HandoverDraftContent(
				"AI가 바꾼 목적", null, null, null, List.of("기존 예외 규칙", "환불 오류는 CS팀이 처리"),
				List.of(), null, null, null, null, null);

		HandoverDraftContent merged = DraftSection.RULES_AND_EXCEPTIONS.merge(BASE, patch);

		assertThat(merged.rulesAndExceptions()).containsExactly("기존 예외 규칙", "환불 오류는 CS팀이 처리");
		assertThat(merged.purpose()).isEqualTo("사람이 고친 목적");
		assertThat(merged.stakeholders()).isEqualTo(BASE.stakeholders());
		assertThat(merged.accessAccounts()).isEqualTo(BASE.accessAccounts());
	}

	@Test
	void mergeTurnsNullListIntoEmpty() {
		HandoverDraftContent patch = new HandoverDraftContent(
				null, null, null, null, null, null, null, null, null, null, null);

		assertThat(DraftSection.STAKEHOLDERS.merge(BASE, patch).stakeholders()).isEmpty();
	}

	@Test
	void onlyKeepsSingleSection() {
		HandoverDraftContent only = DraftSection.ACCESS_ACCOUNTS.only(BASE);

		assertThat(only.accessAccounts()).isEqualTo(BASE.accessAccounts());
		assertThat(only.purpose()).isNull();
		assertThat(only.stakeholders()).isNull();
		assertThat(DraftSection.ACCESS_ACCOUNTS.valueOf(only)).isEqualTo(BASE.accessAccounts());
	}

	@Test
	void detectsEmptySections() {
		assertThat(DraftSection.ONGOING_TASKS.isEmpty(BASE)).isTrue();
		assertThat(DraftSection.PURPOSE.isEmpty(BASE)).isFalse();
		assertThat(DraftSection.PURPOSE.isEmpty(DraftSection.TOOLS.only(BASE))).isTrue();
		assertThat(DraftSection.allEmpty(BASE, List.of(DraftSection.TOOLS, DraftSection.SCHEDULE))).isTrue();
		assertThat(DraftSection.allEmpty(BASE, List.of(DraftSection.TOOLS, DraftSection.PURPOSE))).isFalse();
	}

	@Test
	void mergeWithSectionsReplacesOnlyTargetSections() {
		HandoverDraftContent patch = new HandoverDraftContent(
				"AI가 새로 쓴 목적", null, null, null, List.of("새 규칙"),
				null, null, List.of(), null, List.of("확인 필요: 정산 마감일"),
				List.of(new ConfirmedCriterion("쿠폰 승인", "팀장")));

		HandoverDraftContent merged = DraftSection.merge(BASE, patch,
				List.of(DraftSection.RULES_AND_EXCEPTIONS, DraftSection.CONFIRMED_CRITERIA));

		assertThat(merged.purpose()).isEqualTo("사람이 고친 목적");
		assertThat(merged.firstWeekChecklist()).containsExactly("체크");
		assertThat(merged.rulesAndExceptions()).containsExactly("새 규칙");
		assertThat(merged.confirmedCriteria()).containsExactly(new ConfirmedCriterion("쿠폰 승인", "팀장"));
	}

	@Test
	void mergeWithSectionsTurnsNullPatchIntoEmptyList() {
		HandoverDraftContent patch = new HandoverDraftContent(null, null, null, null, null, null, null, null, null, null, null);

		HandoverDraftContent merged = DraftSection.merge(BASE, patch, List.of(DraftSection.ACCESS_ACCOUNTS));

		assertThat(merged.accessAccounts()).isEmpty();
		assertThat(merged.rulesAndExceptions()).containsExactly("기존 예외 규칙");
	}

	@Test
	void extractKeepsOnlyTargetSections() {
		HandoverDraftContent extracted = DraftSection.extract(BASE, List.of(DraftSection.STAKEHOLDERS));

		assertThat(extracted.stakeholders()).isEqualTo(BASE.stakeholders());
		assertThat(extracted.purpose()).isNull();
		assertThat(extracted.rulesAndExceptions()).isNull();
	}

	@Test
	void describeListsLabelAndFieldName() {
		assertThat(DraftSection.describe(List.of(DraftSection.PURPOSE, DraftSection.RECURRING_TASKS)))
				.isEqualTo("업무 개요(purpose), 반복 업무(recurringTasks)");
	}
}
