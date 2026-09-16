package com.baton.masking;

/**
 * 후보 목록에 보여줄 부분 가림 문자열을 만든다(예: min***@example.com, 010-****-5678).
 * 확정 후에는 DB에서 원문 텍스트를 지우기 때문에, 무엇을 가렸는지는 이 값으로만 알 수 있다.
 * 그래서 원래 값을 되살릴 수 없을 만큼만 남긴다.
 */
final class MaskingPreview {

	private static final int MAX_LENGTH = 100;

	private MaskingPreview() {
	}

	static String of(MaskingType type, String original) {
		String preview = switch (type) {
			case EMAIL -> email(original);
			case CUSTOM -> custom(original);
			default -> keepLastFourDigits(original);
		};
		return preview.length() > MAX_LENGTH ? preview.substring(0, MAX_LENGTH) + "…" : preview;
	}

	/** 아이디 앞 3글자와 도메인만 남긴다. */
	private static String email(String value) {
		int at = value.indexOf('@');
		if (at < 0) {
			return custom(value);
		}
		String local = value.substring(0, at);
		return local.substring(0, Math.min(3, local.length())) + "***" + value.substring(at);
	}

	/** 숫자는 마지막 4자리만 남기고 *로 바꾼다. 하이픈 같은 구분자는 형식을 알아보도록 그대로 둔다. */
	private static String keepLastFourDigits(String value) {
		int digitsToHide = (int) value.chars().filter(Character::isDigit).count() - 4;
		StringBuilder sb = new StringBuilder(value.length());
		for (char c : value.toCharArray()) {
			if (Character.isDigit(c) && digitsToHide > 0) {
				sb.append('*');
				digitsToHide--;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** 직접 지정한 구간은 형식을 알 수 없으니 첫 글자만 남긴다. 줄바꿈은 목록 한 줄에 보이도록 공백으로 바꾼다. */
	private static String custom(String value) {
		String oneLine = value.strip().replaceAll("\\s+", " ");
		if (oneLine.isEmpty()) {
			return "***";
		}
		return oneLine.charAt(0) + "*".repeat(Math.min(oneLine.length() - 1, 10));
	}
}
