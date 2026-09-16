package com.baton.masking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 추출된 원문 텍스트에서 민감정보 후보(이메일·전화번호·계좌번호 등)를 찾는다.
 *
 * 외부 API(OpenAI 등)를 쓰지 않고 서버 안에서 정규식과 검증번호 계산만으로 판단한다.
 * 민감정보를 찾으려고 원문을 외부로 보내면 마스킹의 의미가 없어지기 때문이다.
 * 이름·주소처럼 패턴이 없는 정보는 여기서 잡지 않고, 사용자의 "직접 추가"로 보완한다.
 *
 * 신뢰도(confidence)는 AI 확률이 아니라 규칙별로 정한 점수다. REVIEW_THRESHOLD 미만이면
 * 사용자가 직접 확인해야 하는 후보(needsReview)로 분류한다.
 */
@Component
public class MaskingDetector {

	static final double REVIEW_THRESHOLD = 0.85;

	/** 계좌번호 앞쪽에 이 단어가 있으면 계좌번호일 가능성이 높다고 본다. */
	private static final Set<String> ACCOUNT_KEYWORDS = Set.of("계좌", "은행", "예금주", "입금", "송금", "account");
	private static final Set<String> BUSINESS_NO_KEYWORDS = Set.of("사업자");
	private static final int KEYWORD_WINDOW = 30;

	// 앞뒤에 숫자나 하이픈이 붙어 있으면 더 긴 번호의 일부이므로 매칭하지 않는다.
	private static final String NO_DIGIT_BEFORE = "(?<![\\d-])";
	private static final String NO_DIGIT_AFTER = "(?![\\d-])";

	private static final Pattern EMAIL = Pattern.compile(
			"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
	private static final Pattern RRN = Pattern.compile(
			NO_DIGIT_BEFORE + "(\\d{2})(\\d{2})(\\d{2})-?[1-8]\\d{6}" + NO_DIGIT_AFTER);
	private static final Pattern CARD = Pattern.compile(
			NO_DIGIT_BEFORE + "\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}" + NO_DIGIT_AFTER);
	private static final Pattern MOBILE = Pattern.compile(
			NO_DIGIT_BEFORE + "01[016789](-?)\\d{3,4}\\1\\d{4}" + NO_DIGIT_AFTER);
	private static final Pattern LANDLINE = Pattern.compile(
			NO_DIGIT_BEFORE + "0(?:2|[3-6][1-5]|70|50\\d)(-?)\\d{3,4}\\1\\d{4}" + NO_DIGIT_AFTER);
	private static final Pattern BUSINESS_NO = Pattern.compile(
			NO_DIGIT_BEFORE + "\\d{3}(-?)\\d{2}\\1\\d{5}" + NO_DIGIT_AFTER);
	private static final Pattern ACCOUNT = Pattern.compile(
			NO_DIGIT_BEFORE + "\\d{2,6}(?:-\\d{2,8}){1,4}" + NO_DIGIT_AFTER);

	/**
	 * 텍스트에서 후보를 찾아 시작 위치 순으로 돌려준다. 같은 구간에 여러 규칙이 걸리면
	 * 먼저 검사한(더 확실한) 유형 하나만 남긴다.
	 */
	public List<DetectedCandidate> detect(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}

		List<DetectedCandidate> found = new ArrayList<>();
		findEmails(text, found);
		findResidentNumbers(text, found);
		findCardNumbers(text, found);
		findPhoneNumbers(text, found);
		findBusinessNumbers(text, found);
		findAccountNumbers(text, found);

		found.sort(Comparator.comparingInt(DetectedCandidate::startOffset));
		return List.copyOf(found);
	}

	private void findEmails(String text, List<DetectedCandidate> found) {
		Matcher m = EMAIL.matcher(text);
		while (m.find()) {
			add(found, MaskingType.EMAIL, m.start(), m.end(), 0.98);
		}
	}

	/** 앞 6자리가 실제 날짜가 아니면 버린다. 2020년 10월 이후 발급분은 검증번호 규칙이 없어서, 안 맞으면 확인 필요로 둔다. */
	private void findResidentNumbers(String text, List<DetectedCandidate> found) {
		Matcher m = RRN.matcher(text);
		while (m.find()) {
			int month = Integer.parseInt(m.group(2));
			int day = Integer.parseInt(m.group(3));
			if (month < 1 || month > 12 || day < 1 || day > 31) {
				continue;
			}
			double confidence = isValidResidentNumber(digitsOf(m.group())) ? 0.98 : 0.80;
			add(found, MaskingType.RRN, m.start(), m.end(), confidence);
		}
	}

	/** 카드번호는 Luhn 검사를 통과한 것만 잡는다. 통과 못한 16자리는 계좌번호 규칙에 맡긴다. */
	private void findCardNumbers(String text, List<DetectedCandidate> found) {
		Matcher m = CARD.matcher(text);
		while (m.find()) {
			if (passesLuhn(digitsOf(m.group()))) {
				add(found, MaskingType.CARD, m.start(), m.end(), 0.95);
			}
		}
	}

	/** 휴대폰은 형식이 뚜렷해서 높게, 유선·인터넷전화는 하이픈이 없으면 다른 숫자일 수 있어 낮게 준다. */
	private void findPhoneNumbers(String text, List<DetectedCandidate> found) {
		Matcher mobile = MOBILE.matcher(text);
		while (mobile.find()) {
			double confidence = mobile.group(1).isEmpty() ? 0.90 : 0.96;
			add(found, MaskingType.PHONE, mobile.start(), mobile.end(), confidence);
		}
		Matcher landline = LANDLINE.matcher(text);
		while (landline.find()) {
			double confidence = landline.group(1).isEmpty() ? 0.70 : 0.89;
			add(found, MaskingType.PHONE, landline.start(), landline.end(), confidence);
		}
	}

	/** 하이픈 없는 10자리 숫자는 흔해서, 앞에 "사업자"라는 단어가 있을 때만 사업자번호로 본다. */
	private void findBusinessNumbers(String text, List<DetectedCandidate> found) {
		Matcher m = BUSINESS_NO.matcher(text);
		while (m.find()) {
			boolean hyphenated = !m.group(1).isEmpty();
			boolean hasKeyword = hasKeywordBefore(text, m.start(), BUSINESS_NO_KEYWORDS);
			if (!hyphenated && !hasKeyword) {
				continue;
			}
			double confidence = isValidBusinessNumber(digitsOf(m.group())) ? 0.90 : 0.78;
			add(found, MaskingType.BUSINESS_NO, m.start(), m.end(), confidence);
		}
	}

	/** 계좌번호는 은행마다 형식이 달라 숫자 개수(10~16자리)로만 거르고, 주변 단어가 없으면 확인 필요로 둔다. */
	private void findAccountNumbers(String text, List<DetectedCandidate> found) {
		Matcher m = ACCOUNT.matcher(text);
		while (m.find()) {
			int digitCount = digitsOf(m.group()).length();
			if (digitCount < 10 || digitCount > 16) {
				continue;
			}
			double confidence = hasKeywordBefore(text, m.start(), ACCOUNT_KEYWORDS) ? 0.94 : 0.60;
			add(found, MaskingType.ACCOUNT, m.start(), m.end(), confidence);
		}
	}

	/** 이미 잡힌 후보와 한 글자라도 겹치면 추가하지 않는다. 검사 순서가 곧 우선순위다. */
	private void add(List<DetectedCandidate> found, MaskingType type, int start, int end, double confidence) {
		boolean overlaps = found.stream().anyMatch(c -> start < c.endOffset() && c.startOffset() < end);
		if (!overlaps) {
			found.add(new DetectedCandidate(type, start, end, confidence, confidence < REVIEW_THRESHOLD));
		}
	}

	/** 같은 줄 안에서만 찾는다. 윗줄의 "계좌번호"가 아랫줄의 다른 번호까지 계좌로 판단하게 만들지 않도록. */
	private boolean hasKeywordBefore(String text, int start, Set<String> keywords) {
		int lineStart = text.lastIndexOf('\n', start - 1) + 1;
		String window = text.substring(Math.max(lineStart, start - KEYWORD_WINDOW), start).toLowerCase();
		return keywords.stream().anyMatch(window::contains);
	}

	private static String digitsOf(String value) {
		return value.replaceAll("\\D", "");
	}

	static boolean isValidResidentNumber(String digits) {
		int[] weights = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
		int sum = 0;
		for (int i = 0; i < weights.length; i++) {
			sum += (digits.charAt(i) - '0') * weights[i];
		}
		int check = (11 - sum % 11) % 10;
		return check == digits.charAt(12) - '0';
	}

	static boolean isValidBusinessNumber(String digits) {
		int[] weights = {1, 3, 7, 1, 3, 7, 1, 3, 5};
		int sum = 0;
		for (int i = 0; i < weights.length; i++) {
			sum += (digits.charAt(i) - '0') * weights[i];
		}
		sum += (digits.charAt(8) - '0') * 5 / 10;
		int check = (10 - sum % 10) % 10;
		return check == digits.charAt(9) - '0';
	}

	static boolean passesLuhn(String digits) {
		int sum = 0;
		boolean doubleIt = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			int d = digits.charAt(i) - '0';
			if (doubleIt) {
				d *= 2;
				if (d > 9) {
					d -= 9;
				}
			}
			sum += d;
			doubleIt = !doubleIt;
		}
		return sum % 10 == 0;
	}

	/**
	 * 탐지 결과 한 건. offset은 원문 텍스트 기준 [startOffset, endOffset) 구간이다.
	 * needsReview가 true면 자동으로 확정하지 않고 사용자 확인을 요구한다.
	 */
	public record DetectedCandidate(
			MaskingType type,
			int startOffset,
			int endOffset,
			double confidence,
			boolean needsReview) {
	}
}
