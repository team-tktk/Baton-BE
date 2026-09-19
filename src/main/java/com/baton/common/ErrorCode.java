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

	// ── 검토(review) ────────────────────────────────────────
	REVIEW_CHECKLIST_INCOMPLETE(HttpStatus.CONFLICT, "체크리스트를 모두 완료해야 승인할 수 있습니다"),

	// ── AI / RAG ────────────────────────────────────────────
	AI_UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다"),
	AI_UPLOAD_QUOTA_EXCEEDED(HttpStatus.BAD_REQUEST, "업로드 가능한 파일 개수 또는 용량을 초과했습니다"),
	AI_FILE_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "파일에서 텍스트를 추출하지 못했습니다"),
	AI_SOURCE_DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 문서입니다"),
	AI_NO_DOCUMENTS(HttpStatus.BAD_REQUEST, "분석할 업로드 파일이 없습니다"),
	AI_DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "생성된 인수인계 초안이 없습니다"),
	AI_QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 질문입니다"),
	AI_QUESTION_ANSWER_INVALID(HttpStatus.BAD_REQUEST, "답변하거나 건너뛰기 중 하나를 선택해야 합니다"),
	AI_SOURCE_DOCUMENT_PROCESSING(HttpStatus.CONFLICT, "처리 중인 파일은 삭제할 수 없습니다"),
	AI_EXTERNAL_SOURCE_INVALID_URL(HttpStatus.BAD_REQUEST, "허용되지 않는 웹 주소입니다"),
	AI_EXTERNAL_SOURCE_FETCH_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "웹 자료를 가져오지 못했습니다"),
	AI_EXTERNAL_SOURCE_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY, "저장할 자료 내용이 없습니다"),
	AI_QUESTIONS_INCOMPLETE(HttpStatus.CONFLICT, "답변하지 않은 확인 질문이 있습니다"),
	AI_ANALYSIS_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다"),
	AI_ANALYSIS_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 분석 작업이 진행 중입니다"),
	AI_ANALYSIS_RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "실패한 분석 작업만 재시도할 수 있습니다"),
	AI_DRAFT_REVISION_CONFLICT(HttpStatus.CONFLICT, "문서가 그사이 변경되었습니다. 최신 문서를 확인한 뒤 다시 시도해주세요"),

	// ── AI 사용량(ai-usage) ─────────────────────────────────
	AI_USAGE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AI 요청 한도를 초과했습니다"),
	AI_TASK_ALREADY_RUNNING(HttpStatus.CONFLICT, "같은 AI 작업이 이미 진행 중입니다"),

	// ── 준비도(readiness) ───────────────────────────────────
	READINESS_NOT_EVALUATED(HttpStatus.NOT_FOUND, "아직 준비도 평가 결과가 없습니다"),
	READINESS_STALE(HttpStatus.CONFLICT, "문서가 바뀌어 준비도를 다시 평가해야 합니다"),
	READINESS_ITEM_SUFFICIENT(HttpStatus.CONFLICT, "이미 충분한 항목은 보완할 필요가 없습니다"),
	READINESS_FIX_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 보완안입니다"),
	READINESS_FIX_INVALID_STATE(HttpStatus.CONFLICT, "현재 상태의 보완안에는 할 수 없는 작업입니다"),

	// ── 마스킹 검수(masking) ────────────────────────────────
	MASKING_NOT_IN_REVIEW(HttpStatus.CONFLICT, "마스킹 검수 중인 파일이 아닙니다"),
	MASKING_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 마스킹 항목입니다"),
	MASKING_INVALID_RANGE(HttpStatus.BAD_REQUEST, "마스킹 구간이 문서 범위를 벗어났습니다"),
	MASKING_RANGE_OVERLAP(HttpStatus.CONFLICT, "이미 마스킹 항목이 있는 구간입니다"),
	MASKING_CANDIDATE_NOT_DELETABLE(HttpStatus.CONFLICT, "자동으로 찾은 항목은 삭제할 수 없습니다. 체크를 해제해주세요"),
	MASKING_REVIEW_INCOMPLETE(HttpStatus.CONFLICT, "확인하지 않은 마스킹 항목이 있습니다"),
	MASKING_NOT_CONFIRMED(HttpStatus.CONFLICT, "마스킹 검수를 확정하지 않은 파일이 있습니다");

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
