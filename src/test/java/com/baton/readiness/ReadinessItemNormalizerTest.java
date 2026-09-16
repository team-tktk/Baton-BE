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
				ReadinessArea.EXCEPTION, ReadinessStatus.MISSING, DraftSection.STAKEHOLDERS,
				"환불 오류 발생 시의 세부 처리 절차와 담당자가 명확하지 않다.",
				"환불 오류 대응 담당자가 명확하지 않아요", "환불 오류 처리 절차와 담당자를 확인하세요",
				List.of(new GeneratedEvidence("프로모션 운영 체크리스트.xlsx", "3번 시트, 예외 상황"),
						new GeneratedEvidence("프로모션 운영 체크리스트.xlsx", "3번 시트, 예외 상황"),
						new GeneratedEvidence("없는 파일.pdf", "1쪽")));

		ReadinessItem exception = find(normalizer.normalize(new GeneratedAssessment(List.of(generated)), CONTENT, SOURCES),
				ReadinessArea.EXCEPTION);

		assertThat(exception.status()).isEqualTo(ReadinessStatus.MISSING);
		assertThat(exception.section()).isEqualTo(DraftSection.RULES_AND_EXCEPTIONS);
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

	private static GeneratedAreaAssessment assessment(ReadinessArea area, ReadinessStatus status, DraftSection section,
			String anchor, String summary) {
		return new GeneratedAreaAssessment(area, status, section, anchor, summary, "확인하세요", List.of());
	}

	private static ReadinessItem find(List<ReadinessItem> items, ReadinessArea area) {
		return items.stream().filter(item -> item.area() == area).findFirst().orElseThrow();
	}
}
