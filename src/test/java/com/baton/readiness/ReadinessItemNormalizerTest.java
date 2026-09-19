package com.baton.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baton.ai.DraftSection;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.ai.dto.TaskItem;
import com.baton.readiness.ReadinessItemNormalizer.SourceRef;
import com.baton.readiness.dto.GeneratedAreaAssessment;
import com.baton.readiness.dto.GeneratedAssessment;
import com.baton.readiness.dto.GeneratedEvidence;
import com.baton.readiness.dto.GeneratedItemQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;

class ReadinessItemNormalizerTest {

	private static final UUID CHECKLIST_ID = UUID.randomUUID();
	private static final List<SourceRef> SOURCES = List.of(new SourceRef(CHECKLIST_ID, "프로모션 운영 체크리스트.xlsx"));

	/** 예외 규칙·반복 업무만 있고 관계자·권한·일정 등은 비어 있는 문서. */
	private static final HandoverDraftContent CONTENT = new HandoverDraftContent(
			"프로모션을 기획·운영한다.", "", List.of(),
			List.of(new TaskItem("할인 코드 설정", "매주 반복", "운영툴에서 할인 코드 생성", "월요일 오전 설정", "매주 월요일")),
			List.of("결제 오류 시 고객센터와 협업해 환불한다.", "환불 오류 발생 시의 세부 처리 절차와 담당자가 명확하지 않다."),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

	private final ReadinessItemNormalizer normalizer = new ReadinessItemNormalizer(new ObjectMapper());

	@Test
	void validatesGeneratedQuoteBeforeReturningPdfCoordinates() {
		String text = "Refund approval requires the team lead.";
		var box = new com.baton.ai.dto.EvidenceHighlight(3, .1, .2, .5, .03);
		var source = new SourceRef(CHECKLIST_ID, "manual.pdf", text,
				List.of(new com.baton.ai.PdfTextLocation(0, text.length(), 3, box)));
		var generated = new GeneratedAreaAssessment(ReadinessArea.EXCEPTION, ReadinessStatus.PARTIAL,
				List.of(DraftSection.RULES_AND_EXCEPTIONS), null, "Check approval", "Confirm the owner",
				List.of(new GeneratedEvidence("manual.pdf", "unknown", text),
						new GeneratedEvidence("manual.pdf", "invented", "Anyone may approve refunds.")), List.of());
		var item = find(normalizer.normalize(new GeneratedAssessment(List.of(generated)), CONTENT, List.of(source)),
				ReadinessArea.EXCEPTION);
		assertThat(item.evidence().getFirst().page()).isEqualTo(3);
		assertThat(item.evidence().getFirst().quote()).isEqualTo(text);
		assertThat(item.evidence().getFirst().highlights()).containsExactly(box);
		assertThat(item.evidence().get(1).quote()).isNull();
		assertThat(item.evidence().get(1).highlights()).isEmpty();
	}

	@Test
	void returnsOneItemPerAreaInDefinitionOrder() {
		List<ReadinessItem> items = normalizer.normalize(null, CONTENT, SOURCES);

		assertThat(items).extracting(ReadinessItem::area).containsExactly(ReadinessArea.values());
	}

	@Test
	void emptySectionsAreMissingEvenIfAiSaysSufficient() {
		GeneratedAssessment assessment = new GeneratedAssessment(List.of(
				assessment(ReadinessArea.ACCESS, ReadinessStatus.SUFFICIENT, DraftSection.ACCESS_ACCOUNTS, "", "충분해요")));

		ReadinessItem access = find(normalizer.normalize(assessment, CONTENT, SOURCES), ReadinessArea.ACCESS);

		assertThat(access.status()).isEqualTo(ReadinessStatus.MISSING);
		assertThat(access.summary()).isNotEqualTo("충분해요");
		assertThat(access.section()).isEqualTo(DraftSection.ACCESS_ACCOUNTS);
	}

	@Test
	void evidenceAreaIsMissingWithoutUploadedSources() {
		ReadinessItem evidence = find(normalizer.normalize(null, CONTENT, List.of()), ReadinessArea.EVIDENCE);

		assertThat(evidence.status()).isEqualTo(ReadinessStatus.MISSING);
	}

	@Test
	void areaOmittedByAiWithContentBecomesPartial() {
		ReadinessItem exception = find(normalizer.normalize(new GeneratedAssessment(List.of()), CONTENT, SOURCES),
				ReadinessArea.EXCEPTION);

		assertThat(exception.status()).isEqualTo(ReadinessStatus.PARTIAL);
		assertThat(exception.resolution()).isNotBlank();
	}

	@Test
	void keepsValidAiResultAndDropsInvalidParts() {
		GeneratedAreaAssessment generated = new GeneratedAreaAssessment(
				ReadinessArea.EXCEPTION, ReadinessStatus.MISSING, List.of(DraftSection.STAKEHOLDERS),
				"환불 오류 발생 시의 세부 처리 절차와 담당자가 명확하지 않다.",
				"환불 오류 대응 담당자가 명확하지 않아요", "환불 오류 처리 절차와 담당자를 확인하세요",
				List.of(new GeneratedEvidence("프로모션 운영 체크리스트.xlsx", "3번 시트, 예외 상황"),
						new GeneratedEvidence("프로모션 운영 체크리스트.xlsx", "3번 시트, 예외 상황"),
						new GeneratedEvidence("없는 파일.pdf", "1쪽")),
				List.of());

		ReadinessItem exception = find(normalizer.normalize(new GeneratedAssessment(List.of(generated)), CONTENT, SOURCES),
				ReadinessArea.EXCEPTION);

		assertThat(exception.status()).isEqualTo(ReadinessStatus.MISSING);
		assertThat(exception.section()).isEqualTo(DraftSection.RULES_AND_EXCEPTIONS);
		assertThat(exception.targetSections()).containsExactly(DraftSection.RULES_AND_EXCEPTIONS);
		assertThat(exception.anchorText()).isEqualTo("환불 오류 발생 시의 세부 처리 절차와 담당자가 명확하지 않다.");
		assertThat(exception.summary()).isEqualTo("환불 오류 대응 담당자가 명확하지 않아요");
		assertThat(exception.evidence()).containsExactly(
				new ReadinessEvidence(CHECKLIST_ID, "프로모션 운영 체크리스트.xlsx", "3번 시트, 예외 상황"));
	}

	@Test
	void dropsAnchorTextNotInSection() {
		GeneratedAreaAssessment generated = assessment(ReadinessArea.PROCEDURE, ReadinessStatus.PARTIAL,
				DraftSection.RECURRING_TASKS, "문서에 없는 문장", "순서가 부족해요");

		ReadinessItem procedure = find(normalizer.normalize(new GeneratedAssessment(List.of(generated)), CONTENT, SOURCES),
				ReadinessArea.PROCEDURE);

		assertThat(procedure.anchorText()).isNull();
		assertThat(normalizer.anchorIn(CONTENT, DraftSection.RECURRING_TASKS, " 월요일 오전 설정 "))
				.isEqualTo("월요일 오전 설정");
	}

	@Test
	void targetSectionsFollowResolutionWithinArea() {
		GeneratedAreaAssessment generated = new GeneratedAreaAssessment(ReadinessArea.PROCEDURE, ReadinessStatus.PARTIAL,
				List.of(DraftSection.RECURRING_TASKS, DraftSection.STAKEHOLDERS, DraftSection.FIRST_WEEK_CHECKLIST,
						DraftSection.ONGOING_TASKS),
				"", "순서가 부족해요", "반복 업무에 단계별 절차를 적어주세요", List.of(), List.of());

		ReadinessItem procedure = find(normalizer.normalize(new GeneratedAssessment(List.of(generated)), CONTENT, SOURCES),
				ReadinessArea.PROCEDURE);

		// 영역 밖 섹션(주요 관계자)은 버리고 최대 2개, 순서 유지. "문서에서 수정하기" 위치는 첫 번째.
		assertThat(procedure.targetSections())
				.containsExactly(DraftSection.RECURRING_TASKS, DraftSection.FIRST_WEEK_CHECKLIST);
		assertThat(procedure.section()).isEqualTo(DraftSection.RECURRING_TASKS);
	}

	@Test
	void conflictAlwaysTargetsConfirmedCriteriaAndAsksWhichIsRight() {
		GeneratedAreaAssessment generated = new GeneratedAreaAssessment(ReadinessArea.EXCEPTION, ReadinessStatus.CONFLICT,
				List.of(DraftSection.RULES_AND_EXCEPTIONS), "", "자료 간 환불 기준이 달라요", "기준을 확정해 주세요",
				List.of(), List.of());

		ReadinessItem exception = find(normalizer.normalize(new GeneratedAssessment(List.of(generated)), CONTENT, SOURCES),
				ReadinessArea.EXCEPTION);

		assertThat(exception.targetSections())
				.containsExactly(DraftSection.RULES_AND_EXCEPTIONS, DraftSection.CONFIRMED_CRITERIA);
		assertThat(exception.questions()).singleElement()
				.satisfies(question -> assertThat(question.question()).contains("어느 쪽이 맞나요"));
	}

	@Test
	void keepsAtMostThreeUniqueQuestionsAndNoneWhenSufficient() {
		List<GeneratedItemQuestion> questions = List.of(
				new GeneratedItemQuestion("승인자는 누구인가요?", "자료에 없어요", List.of("A.pdf: 팀장", "A.pdf: 팀장", " ")),
				new GeneratedItemQuestion("승인자는 누구인가요?", "중복", List.of()),
				new GeneratedItemQuestion(" ", "빈 질문", List.of()),
				new GeneratedItemQuestion("기한은 언제인가요?", null, null),
				new GeneratedItemQuestion("보고 대상은 누구인가요?", null, null),
				new GeneratedItemQuestion("네 번째 질문인가요?", null, null));

		List<ItemQuestion> partial = ReadinessItemNormalizer.questions(ReadinessArea.CONTACTS, ReadinessStatus.PARTIAL, questions);

		assertThat(partial).extracting(ItemQuestion::question)
				.containsExactly("승인자는 누구인가요?", "기한은 언제인가요?", "보고 대상은 누구인가요?");
		assertThat(partial.get(0).options()).containsExactly("A.pdf: 팀장");
		assertThat(ReadinessItemNormalizer.questions(ReadinessArea.CONTACTS, ReadinessStatus.SUFFICIENT, questions)).isEmpty();
	}

	@Test
	void replacesCodeNamesInUserFacingText() {
		GeneratedAreaAssessment generated = new GeneratedAreaAssessment(ReadinessArea.EXCEPTION, ReadinessStatus.PARTIAL,
				List.of(DraftSection.RULES_AND_EXCEPTIONS), "", "RULES_AND_EXCEPTIONS에 절차가 부족해요",
				"확정한 기준을 CONFIRMED_CRITERIA에 적고 recurringTasks도 고치세요", List.of(),
				List.of(new GeneratedItemQuestion("rulesAndExceptions에 적을 담당자는?", null, List.of())));

		ReadinessItem exception = find(normalizer.normalize(new GeneratedAssessment(List.of(generated)), CONTENT, SOURCES),
				ReadinessArea.EXCEPTION);

		assertThat(exception.summary()).isEqualTo("업무 기준과 예외에 절차가 부족해요");
		assertThat(exception.resolution()).isEqualTo("확정한 기준을 확인된 업무 기준에 적고 반복 업무도 고치세요");
		assertThat(exception.questions().get(0).question()).isEqualTo("업무 기준과 예외에 적을 담당자는?");
	}

	private static GeneratedAreaAssessment assessment(ReadinessArea area, ReadinessStatus status, DraftSection section,
			String anchor, String summary) {
		return new GeneratedAreaAssessment(area, status, List.of(section), anchor, summary, "확인하세요", List.of(), List.of());
	}

	private static ReadinessItem find(List<ReadinessItem> items, ReadinessArea area) {
		return items.stream().filter(item -> item.area() == area).findFirst().orElseThrow();
	}
}
