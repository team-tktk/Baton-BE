package com.baton.handover;

/**
 * 인수자의 수신 상태. 본 상태(HandoverStatus)와 독립적으로 "인수자가 문서를 열어봤는가"만 나타낸다.
 * 관리자(REVIEWER) 참여자에게는 의미가 없어 null로 둔다.
 */
public enum ReceiptStatus {
	UNREAD,  // 아직 열어보지 않음
	READ     // acknowledge 호출로 열어봄
}
