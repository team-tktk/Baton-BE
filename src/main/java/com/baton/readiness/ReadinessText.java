package com.baton.readiness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.baton.ai.DraftSection;

/**
 * 사용자에게 보이는 AI 문구(이유·해결 방법·질문·변경 요약)에 섞여 나온 코드명을 화면 이름으로 바꾼다.
 * 프롬프트에서 막더라도 모델이 코드명(RULES_AND_EXCEPTIONS, recurringTasks 등)을 그대로 쓰는 경우가 있어 서버에서 한 번 더 거른다.
 */
final class ReadinessText {

	private record Replacement(Pattern pattern, String label) {
	}

	/** 긴 코드부터 바꿔야 짧은 코드가 긴 코드의 일부를 먼저 바꾸지 않는다. 섹션·영역 이름이 같으면(SCHEDULE) 섹션 라벨을 쓴다. */
	private static final List<Replacement> REPLACEMENTS = buildReplacements();

	private ReadinessText() {
	}

	/** 코드명을 라벨로 바꾸고 앞뒤 공백을 지운다. 비어 있으면 null. */
	static String plain(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String result = text.strip();
		for (Replacement replacement : REPLACEMENTS) {
			result = replacement.pattern().matcher(result).replaceAll(Matcher.quoteReplacement(replacement.label()));
		}
		return result;
	}

	private static List<Replacement> buildReplacements() {
		record Code(String code, String label) {
		}
		List<Code> codes = new ArrayList<>();
		for (DraftSection section : DraftSection.values()) {
			codes.add(new Code(section.name(), section.getLabel()));
			codes.add(new Code(section.getFieldName(), section.getLabel()));
		}
		for (ReadinessArea area : ReadinessArea.values()) {
			if (codes.stream().noneMatch(code -> code.code().equals(area.name()))) {
				codes.add(new Code(area.name(), area.getLabel()));
			}
		}
		for (ReadinessStatus status : ReadinessStatus.values()) {
			codes.add(new Code(status.name(), status.getLabel()));
		}
		return codes.stream()
				.sorted(Comparator.comparingInt((Code code) -> code.code().length()).reversed())
				.map(code -> new Replacement(
						// 영문자·숫자·밑줄로 이어지지 않는 독립된 코드만 바꾼다. "(필드명)" 같은 괄호 안 표기도 포함.
						Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(code.code()) + "(?![A-Za-z0-9_])"),
						code.label()))
				.toList();
	}
}
