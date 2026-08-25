package com.baton.common;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 카탈로그. 프론트/백엔드가 공유하는 단일 출처(single source of truth).
 *
 * 규칙: 새 에러가 필요하면 여기에 상수를 추가한다. 각자 코드 문자열을 즉석에서 짓지 않는다.
 * 응답의 "code" 값은 이 enum의 이름(name())이 그대로 나간다 → 프론트는 code로 분기.
 *
 * 네이밍: 도메인_사유 (예: AUTH_EMAIL_DUPLICATE). 공통은 접두어 없이.
 */
public enum ErrorCode {

	// ── 공통 ────────────────────────────────────────────────
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),
	CONFLICT(HttpStatus.CONFLICT, "요청이 현재 상태와 충돌합니다"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),

	// ── 인증/회원 ───────────────────────────────────────────
	AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
	AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
	AUTH_EMAIL_DUPLICATE(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다"),

	// ── 인수인계(handover) ──────────────────────────────────
	HANDOVER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 인수인계입니다"),
	HANDOVER_FORBIDDEN(HttpStatus.FORBIDDEN, "해당 인수인계에 접근할 권한이 없습니다"),
	HANDOVER_NOT_EDITABLE(HttpStatus.CONFLICT, "제출 이후에는 이 방식으로 수정/삭제할 수 없습니다"),
	HANDOVER_INVALID_PARTICIPANT(HttpStatus.BAD_REQUEST, "존재하지 않는 사용자를 참여자로 지정했습니다"),
	HANDOVER_INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 할 수 없는 작업입니다"),

	// ── AI / RAG ────────────────────────────────────────────
	AI_UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다"),
	AI_FILE_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "파일에서 텍스트를 추출하지 못했습니다"),
	AI_SOURCE_DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 문서입니다"),
	AI_NO_DOCUMENTS(HttpStatus.BAD_REQUEST, "분석할 업로드 파일이 없습니다"),
	AI_DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "생성된 인수인계 초안이 없습니다"),
	AI_QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 질문입니다"),
	AI_QUESTION_ANSWER_INVALID(HttpStatus.BAD_REQUEST, "답변하거나 건너뛰기 중 하나를 선택해야 합니다"),
	AI_QUESTIONS_INCOMPLETE(HttpStatus.CONFLICT, "답변하지 않은 확인 질문이 있습니다"),
	AI_ANALYSIS_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다"),
	AI_ANALYSIS_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 분석 작업이 진행 중입니다"),
	AI_ANALYSIS_RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "실패한 분석 작업만 재시도할 수 있습니다");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	/** 사용자에게 보여줄 기본 메시지. 상황별로 덮어쓰려면 BusinessException(code, detail) 사용. */
	public String getMessage() {
		return message;
	}
}
