package com.baton.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.baton.ai.dto.GeneratedQuestion;

/**
 * AI가 만든 질문 후보를 중요도순으로 정렬하고, 같은 뜻의 질문(서로 또는 이미 물어본 질문과)을 거른다.
 * 프롬프트로도 중복을 막지만 모델이 표현만 바꿔 같은 질문을 내는 경우가 있어 서버에서 한 번 더 거른다.
 * 최대 개수(MAX_QUESTIONS) 자르기는 호출하는 쪽에서 한다.
 */
final class QuestionSelector {

	static final int MAX_QUESTIONS = 5;

	/** 핵심 단어 Dice 유사도가 이 이상이면 같은 뜻으로 본다. */
	private static final double DUPLICATE_THRESHOLD = 0.7;

	/** 질문마다 붙는 의문사·어미. 이 단어가 겹친다고 같은 질문은 아니므로 비교에서 뺀다. */
	private static final Set<String> QUESTION_WORDS = Set.of(
			"누가", "누구", "누구인가요", "언제", "언제인가요", "어디", "어디서", "어디에", "무엇", "무엇인가요", "뭔가요",
			"어떻게", "어떤", "왜", "있나요", "있습니까", "있는지", "하나요", "합니까", "하는지", "인가요", "되나요", "됩니까",
			"알려주세요", "주세요");

	/** 단어 끝 조사. "마감일은"과 "마감일이"를 같은 단어로 보기 위해 뗀다. 긴 것부터 확인한다. */
	private static final List<String> PARTICLES = List.of(
			"에서", "으로", "에게", "까지", "부터", "은", "는", "이", "가", "을", "를", "의", "에", "와", "과", "도", "로");

	private QuestionSelector() {
	}

	static List<GeneratedQuestion> dedupe(List<GeneratedQuestion> candidates, Collection<String> askedQuestions) {
		List<Set<String>> seen = new ArrayList<>(askedQuestions.stream().map(QuestionSelector::keywords).toList());
		List<GeneratedQuestion> selected = new ArrayList<>();

		List<GeneratedQuestion> ordered = candidates.stream()
				.filter(q -> q.questionText() != null && !q.questionText().isBlank())
				.sorted(Comparator.comparing(q -> q.priority() == null ? Integer.MAX_VALUE : q.priority()))
				.toList();

		for (GeneratedQuestion candidate : ordered) {
			Set<String> words = keywords(candidate.questionText());
			if (seen.stream().anyMatch(other -> isDuplicate(words, other))) {
				continue;
			}
			seen.add(words);
			selected.add(candidate);
		}
		return selected;
	}

	private static boolean isDuplicate(Set<String> a, Set<String> b) {
		if (a.isEmpty() || b.isEmpty()) {
			return a.equals(b);
		}
		Set<String> intersection = new HashSet<>(a);
		intersection.retainAll(b);
		double dice = 2.0 * intersection.size() / (a.size() + b.size());
		return dice >= DUPLICATE_THRESHOLD;
	}

	/**
	 * 질문에서 비교할 핵심 단어만 뽑는다. 문장부호를 지우고, 의문사·어미를 빼고, 단어 끝 조사를 뗀다.
	 * 짧은 질문은 어미가 대부분이라 글자 단위로 비교하면 "쿠폰 승인은 누가"와 "반품 승인은 누가"가 같게 나온다.
	 */
	private static Set<String> keywords(String text) {
		Set<String> words = new HashSet<>();
		if (text == null) {
			return words;
		}
		for (String token : text.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ").split("\\s+")) {
			if (token.isEmpty() || QUESTION_WORDS.contains(token)) {
				continue;
			}
			words.add(stripParticle(token));
		}
		return words;
	}

	private static String stripParticle(String token) {
		for (String particle : PARTICLES) {
			if (token.length() > particle.length() + 1 && token.endsWith(particle)) {
				return token.substring(0, token.length() - particle.length());
			}
		}
		return token;
	}
}
