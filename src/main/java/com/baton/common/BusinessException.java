package com.baton.common;

/**
 * 비즈니스 규칙 위반을 표현하는 공통 예외.
 * 서비스 계층에선 이 예외 하나만 던지면 되고, ProblemDetail 변환은 GlobalExceptionHandler가 전담한다.
 *
 * 사용: throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATE);
 *      throw new BusinessException(ErrorCode.NOT_FOUND, "문서를 찾을 수 없습니다: " + id);
 */
public class BusinessException extends RuntimeException {

	private final transient ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	/** 기본 메시지 대신 상황에 맞는 detail을 줄 때. */
	public BusinessException(ErrorCode errorCode, String detail) {
		super(detail);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
