package com.baton.handover;

/**
 * 인수자의 "받은 인수인계" 목록 필터. 본 상태(HandoverStatus)가 아니라 인수자 관점의 진행 단계다.
 *
 * - UNREAD:      아직 열어보지 않음(receiptStatus=UNREAD, 완료 전)
 * - IN_PROGRESS: 열어봤고 진행 중(receiptStatus=READ, 완료 전)
 * - COMPLETED:   인수인계 완료(status=COMPLETED, 열람 여부와 무관하게 우선)
 */
public enum ReceivedFilter {
	UNREAD,
	IN_PROGRESS,
	COMPLETED
}
