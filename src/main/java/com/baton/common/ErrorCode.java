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

	// ── AI / RAG ────────────────────────────────────────────
	AI_UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다"),
	AI_FILE_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "파일에서 텍스트를 추출하지 못했습니다"),
	AI_SOURCE_DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 문서입니다");

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
