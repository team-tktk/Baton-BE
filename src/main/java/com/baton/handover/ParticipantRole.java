package com.baton.handover;

/**
 * 인수인계 참여자의 역할. 인계자(작성자)는 Handover.ownerId로 따로 관리하므로
 * 이 enum에는 넣지 않는다. 참여자 테이블은 인수자/관리자만 담는다.
 */
public enum ParticipantRole {
	RECIPIENT,  // 인수자 — 문서를 전달받아 조회/질문
	REVIEWER    // 관리자 — 검토/코멘트/승인
}
