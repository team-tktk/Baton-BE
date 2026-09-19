package com.baton.readiness;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.baton.ai.DraftSection;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.readiness.dto.GeneratedAreaAssessment;
import com.baton.readiness.dto.GeneratedAssessment;
import com.baton.readiness.dto.GeneratedEvidence;
import com.baton.readiness.dto.GeneratedItemQuestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * AI 평가 결과를 저장 가능한 ReadinessItem으로 바로잡는다. AI 출력을 그대로 믿지 않고 서버 규칙을 먼저 적용한다:
 * - 영역의 문서 섹션이 전부 비어 있으면(근거와 최신성은 업로드 자료가 없으면) AI 판단과 무관하게 MISSING.
 * - AI가 빠뜨린 영역은 섹션이 비었으면 MISSING, 아니면 PARTIAL(다시 확인 필요)로 채운다.
 * - 영역에 속하지 않는 섹션, 문서에 없는 강조 문장, 업로드되지 않은 근거 파일은 버린다.
 * - 충돌 영역은 고칠 섹션에 확인된 업무 기준을 넣고, 질문이 없으면 어느 값이 맞는지 묻는 질문을 붙인다.
 * - 사용자에게 보이는 문구의 코드명(섹션·영역 코드, 필드명)은 화면 이름으로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class ReadinessItemNormalizer {

	private static final String DEFAULT_MISSING_SUMMARY = "%s에 대한 내용이 문서에 없어요.";
	private static final String DEFAULT_MISSING_RESOLUTION = "%s 내용을 확인해 문서에 추가해주세요.";
	private static final String DEFAULT_UNKNOWN_SUMMARY = "%s을(를) 판단하지 못했어요.";
	private static final String DEFAULT_UNKNOWN_RESOLUTION = "%s 내용이 충분한지 직접 확인해주세요.";
	private static final String DEFAULT_CONFLICT_QUESTION = "자료마다 다르게 적힌 %s 기준 중 어느 쪽이 맞나요?";
	private static final String DEFAULT_CONFLICT_REASON = "확정해야 문서에 한 가지로 적을 수 있어요";
	private static final int MAX_TARGET_SECTIONS = 2;
	private static final int MAX_QUESTIONS = 3;

	private final ObjectMapper objectMapper;

	public record SourceRef(UUID id, String fileName) {
	}

	public List<ReadinessItem> normalize(GeneratedAssessment assessment, HandoverDraftContent content,
			List<SourceRef> sources) {
		Map<ReadinessArea, GeneratedAreaAssessment> byArea = new EnumMap<>(ReadinessArea.class);
		if (assessment != null && assessment.areas() != null) {
			for (GeneratedAreaAssessment generated : assessment.areas()) {
				if (generated != null && generated.area() != null) {
					byArea.putIfAbsent(generated.area(), generated);
				}
			}
		}
		Map<String, UUID> sourceIdByName = new LinkedHashMap<>();
		sources.forEach(source -> sourceIdByName.putIfAbsent(source.fileName(), source.id()));

		List<ReadinessItem> items = new ArrayList<>();
		for (ReadinessArea area : ReadinessArea.values()) {
			items.add(normalizeArea(area, byArea.get(area), content, sourceIdByName));
		}
		return items;
	}

	private ReadinessItem normalizeArea(ReadinessArea area, GeneratedAreaAssessment generated,
			HandoverDraftContent content, Map<String, UUID> sourceIdByName) {
		boolean empty = area == ReadinessArea.EVIDENCE
				? sourceIdByName.isEmpty()
				: DraftSection.allEmpty(content, area.getSections());

		if (empty) {
			// AI도 누락으로 봤을 때만 AI 문구를 쓰고, 다른 상태를 냈다면 그 설명은 맞지 않으니 기본 문구로 둔다.
			GeneratedAreaAssessment agreed = (generated != null && generated.status() == ReadinessStatus.MISSING)
					? generated : null;
			List<DraftSection> sections = targetSections(area, ReadinessStatus.MISSING,
					agreed == null ? null : agreed.targetSections());
			return new ReadinessItem(area, ReadinessStatus.MISSING, sections.get(0), null,
					textOr(agreed == null ? null : agreed.summary(), DEFAULT_MISSING_SUMMARY.formatted(area.getLabel())),
					textOr(agreed == null ? null : agreed.resolution(),
							DEFAULT_MISSING_RESOLUTION.formatted(area.getLabel())),
					agreed == null ? List.of() : evidence(agreed.evidence(), sourceIdByName),
					sections,
					questions(area, ReadinessStatus.MISSING, agreed == null ? null : agreed.questions()));
		}

		if (generated == null || generated.status() == null) {
			List<DraftSection> sections = List.of(area.primarySection());
			return new ReadinessItem(area, ReadinessStatus.PARTIAL, area.primarySection(), null,
					DEFAULT_UNKNOWN_SUMMARY.formatted(area.getLabel()),
					DEFAULT_UNKNOWN_RESOLUTION.formatted(area.getLabel()),
					List.of(), sections, List.of());
		}

		ReadinessStatus status = generated.status();
		List<DraftSection> sections = targetSections(area, status, generated.targetSections());
		String resolution = status == ReadinessStatus.SUFFICIENT
				? ReadinessText.plain(generated.resolution())
				: textOr(generated.resolution(), DEFAULT_UNKNOWN_RESOLUTION.formatted(area.getLabel()));
		return new ReadinessItem(area, status, sections.get(0),
				anchorIn(content, sections, generated.anchorText()),
				textOr(generated.summary(), status.getLabel()),
				resolution,
				evidence(generated.evidence(), sourceIdByName),
				sections,
				questions(area, status, generated.questions()));
	}

	/**
	 * 보완안이 고칠 섹션. 이 영역에 속한 섹션만 최대 2개 남기고, 없으면 기본 섹션.
	 * 충돌은 확정한 값을 확인된 업무 기준에 적어야 풀리므로(평가 규칙) 항상 확인된 업무 기준을 포함한다.
	 */
	static List<DraftSection> targetSections(ReadinessArea area, ReadinessStatus status, List<DraftSection> generated) {
		List<DraftSection> sections = new ArrayList<>();
		if (generated != null) {
			generated.stream()
					.filter(section -> section != null && area.getSections().contains(section))
					.distinct()
					.limit(MAX_TARGET_SECTIONS)
					.forEach(sections::add);
		}
		if (sections.isEmpty()) {
			sections.add(area.primarySection());
		}
		if (status == ReadinessStatus.CONFLICT && !sections.contains(DraftSection.CONFIRMED_CRITERIA)) {
			sections.add(DraftSection.CONFIRMED_CRITERIA);
		}
		return List.copyOf(sections);
	}

	/** 충분한 영역은 질문이 없다. 충돌인데 질문이 없으면 어느 값이 맞는지 묻는 기본 질문을 붙인다. */
	static List<ItemQuestion> questions(ReadinessArea area, ReadinessStatus status, List<GeneratedItemQuestion> generated) {
		if (status == ReadinessStatus.SUFFICIENT) {
			return List.of();
		}
		Map<String, ItemQuestion> unique = new LinkedHashMap<>();
		if (generated != null) {
			for (GeneratedItemQuestion question : generated) {
				String text = question == null ? null : ReadinessText.plain(question.question());
				if (text == null || unique.size() >= MAX_QUESTIONS) {
					continue;
				}
				List<String> options = question.options() == null ? List.of() : question.options().stream()
						.map(ReadinessText::plain)
						.filter(option -> option != null)
						.distinct()
						.toList();
				unique.putIfAbsent(text, new ItemQuestion(text, ReadinessText.plain(question.reason()), options));
			}
		}
		if (unique.isEmpty() && status == ReadinessStatus.CONFLICT) {
			String text = DEFAULT_CONFLICT_QUESTION.formatted(area.getLabel());
			unique.put(text, new ItemQuestion(text, DEFAULT_CONFLICT_REASON, List.of()));
		}
		return List.copyOf(unique.values());
	}

	/** 강조 문장은 고칠 섹션 중 하나의 실제 문자열 안에 있을 때만 남긴다(프론트가 그대로 찾아 하이라이트한다). */
	String anchorIn(HandoverDraftContent content, List<DraftSection> sections, String anchorText) {
		for (DraftSection section : sections) {
			String anchor = anchorIn(content, section, anchorText);
			if (anchor != null) {
				return anchor;
			}
		}
		return null;
	}

	/** 강조 문장은 해당 섹션의 실제 문자열 안에 있을 때만 남긴다(프론트가 그대로 찾아 하이라이트한다). */
	String anchorIn(HandoverDraftContent content, DraftSection section, String anchorText) {
		String anchor = blankToNull(anchorText);
		if (anchor == null) {
			return null;
		}
		Object value = section.valueOf(content);
		if (value == null) {
			return null;
		}
		return containsText(objectMapper.valueToTree(value), anchor.strip()) ? anchor.strip() : null;
	}

	private boolean containsText(JsonNode node, String text) {
		if (node.isTextual()) {
			return node.asText().contains(text);
		}
		for (JsonNode child : node) {
			if (containsText(child, text)) {
				return true;
			}
		}
		return false;
	}

	private List<ReadinessEvidence> evidence(List<GeneratedEvidence> generated, Map<String, UUID> sourceIdByName) {
		if (generated == null) {
			return List.of();
		}
		Map<String, ReadinessEvidence> unique = new LinkedHashMap<>();
		for (GeneratedEvidence evidence : generated) {
			if (evidence == null || evidence.fileName() == null) {
				continue;
			}
			String fileName = evidence.fileName().strip();
			UUID sourceId = sourceIdByName.get(fileName);
			if (sourceId == null) {
				continue;
			}
			String locator = blankToNull(evidence.locator());
			unique.putIfAbsent(fileName + "|" + locator, new ReadinessEvidence(sourceId, fileName, locator));
		}
		return List.copyOf(unique.values());
	}

	private static String textOr(String text, String fallback) {
		String value = ReadinessText.plain(text);
		return value == null ? fallback : value;
	}

	private static String blankToNull(String text) {
		return (text == null || text.isBlank()) ? null : text.strip();
	}
}
