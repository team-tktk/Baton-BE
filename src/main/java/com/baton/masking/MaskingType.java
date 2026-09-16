package com.baton.masking;

/**
 * 마스킹 후보의 유형. 프론트/백엔드가 공유하는 값이므로 이름(name())을 그대로 JSON에 싣는다.
 * label은 확정 시 원문을 치환하는 토큰(예: [이메일#1])과 화면 표시에 쓴다.
 */
public enum MaskingType {

	EMAIL("이메일"),
	PHONE("전화번호"),
	ACCOUNT("계좌번호"),
	RRN("주민등록번호"),
	CARD("카드번호"),
	BUSINESS_NO("사업자등록번호"),
	CUSTOM("직접 마스킹");  // 사용자가 직접 지정한 구간

	private final String label;

	MaskingType(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
