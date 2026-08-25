package com.baton.handover;

/**
 * 인수인계 진행 상태. 프론트/백엔드가 공유하는 값이므로 이름(name())을 그대로 JSON에 싣는다.
 *
 * 흐름: DRAFT → ANALYZING → ANSWERING → EDITING → PENDING_REVIEW
 *       → (REVISION_REQUESTED ↔ EDITING) → APPROVED → COMPLETED
 *
 * 수신 여부(UNREAD/READ)는 이 상태와 별개로 참여자(HandoverParticipant)에서 관리한다.
 */
public enum HandoverStatus {

	DRAFT,               // 인계자가 작성 중(제출 전). 이 상태에서만 기본정보 수정/삭제 허용.
	ANALYZING,           // AI가 업로드 문서 분석 중
	ANSWERING,           // AI 보완 질문에 답하는 중
	EDITING,             // 초안 확인/수정 중
	PENDING_REVIEW,      // 제출됨, 관리자 검토 대기
	REVISION_REQUESTED,  // 관리자가 보완 요청
	APPROVED,            // 관리자 승인
	COMPLETED;           // 인수자가 인수인계 완료 처리

	/** 인계자가 기본 정보(제목/참여자/업무범위)를 자유롭게 고치고 삭제까지 할 수 있는 단계인가. */
	public boolean isEditableDraft() {
		return this == DRAFT;
	}
}
