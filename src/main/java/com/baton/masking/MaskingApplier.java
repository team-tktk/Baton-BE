package com.baton.masking;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import com.baton.ai.PdfTextLocation;

/**
 * 적용(applied)된 구간을 [유형#번호] 토큰으로 바꾼 텍스트를 만든다. 확정 후에는 이 텍스트만 임베딩·분석에 쓰인다.
 *
 * 같은 파일 안에서 같은 유형·같은 값이면 같은 번호를 붙인다. 원래 값은 감추되,
 * "두 곳에 나온 이메일이 같은 사람의 것"이라는 맥락은 AI가 알 수 있게 하기 위함이다.
 * 번호는 유형별로 원문에 처음 나온 순서대로 1부터 매긴다.
 */
final class MaskingApplier {

	private MaskingApplier() {
	}

	static String apply(String text, List<MaskingCandidate> candidates) {
		return applyWithLocations(text, candidates, List.of()).text();
	}

	static Result applyWithLocations(String text, List<MaskingCandidate> candidates, List<PdfTextLocation> locations) {
		List<MaskingCandidate> applied = candidates.stream()
				.filter(MaskingCandidate::isApplied)
				.sorted(Comparator.comparingInt(MaskingCandidate::getStartOffset))
				.toList();

		Map<MaskingType, Map<String, Integer>> numbers = new EnumMap<>(MaskingType.class);
		StringBuilder sb = new StringBuilder(text.length());
		int cursor = 0;
		List<Replacement> replacements = new ArrayList<>();
		for (MaskingCandidate candidate : applied) {
			// 겹침은 저장 단계에서 막지만, 혹시 겹치면 앞 항목에 이미 포함된 것으로 보고 건너뛴다.
			if (candidate.getStartOffset() < cursor) {
				continue;
			}
			String original = text.substring(candidate.getStartOffset(), candidate.getEndOffset());
			Map<String, Integer> byValue = numbers.computeIfAbsent(candidate.getType(), t -> new HashMap<>());
			int number = byValue.computeIfAbsent(normalize(original), v -> byValue.size() + 1);

			String token = "[" + tokenLabel(candidate.getType()) + "#" + number + "]";
			sb.append(text, cursor, candidate.getStartOffset()).append(token);
			replacements.add(new Replacement(candidate.getStartOffset(), candidate.getEndOffset(), token.length()));
			cursor = candidate.getEndOffset();
		}
		sb.append(text.substring(cursor));
		List<PdfTextLocation> remapped = locations == null ? List.of() : locations.stream().map(location -> {
			boolean masked = replacements.stream().anyMatch(r -> r.start() < location.end() && location.start() < r.end());
			return new PdfTextLocation(offset(location.start(), false, replacements),
					offset(location.end(), true, replacements), location.page(), masked ? null : location.highlight());
		}).toList();
		return new Result(sb.toString(), remapped);
	}

	private static int offset(int original, boolean end, List<Replacement> replacements) {
		int shift = 0;
		for (Replacement r : replacements) {
			if (original <= r.start()) break;
			if (original < r.end()) return r.start() + shift + (end ? r.length() : 0);
			shift += r.length() - (r.end() - r.start());
		}
		return original + shift;
	}

	record Result(String text, List<PdfTextLocation> locations) { }
	private record Replacement(int start, int end, int length) { }

	/** 직접 지정한 구간은 무엇인지 알 수 없으므로 "비공개"로 표시한다. */
	private static String tokenLabel(MaskingType type) {
		return type == MaskingType.CUSTOM ? "비공개" : type.getLabel();
	}

	/** 010-1234-5678과 01012345678, 대소문자만 다른 이메일을 같은 값으로 본다. */
	private static String normalize(String value) {
		return value.strip().toLowerCase().replaceAll("[\\s-]", "");
	}
}
