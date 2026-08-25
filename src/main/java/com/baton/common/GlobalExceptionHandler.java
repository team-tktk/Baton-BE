package com.baton.common;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 에러를 한 곳에서 ProblemDetail(RFC 9457)로 변환하는 단일 지점.
 * 개별 컨트롤러/서비스는 예외만 던지고 여기서 모양을 맞춘다 → 두 명이 짜도 응답이 안 어긋난다.
 *
 * 응답 형식: ProblemDetail + 공통 규칙에 따른 code(항상), fieldErrors(검증 실패 시).
 *
 * ResponseEntityExceptionHandler를 상속하는 이유: 잘못된 JSON 바디, 지원 안 하는 메서드/타입,
 * 404 등 스프링 MVC 표준 예외를 프레임워크가 올바른 4xx ProblemDetail로 처리하게 두기 위함.
 * (직접 Exception.class로 잡으면 이런 것들이 전부 500으로 뭉개진다.)
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** 공통 ProblemDetail 생성기 — code를 항상 붙인다. */
	private static ProblemDetail problemOf(ErrorCode code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatus(code.getStatus());
		problem.setTitle(code.getMessage());
		problem.setDetail(detail);
		problem.setProperty("code", code.name());
		return problem;
	}

	/** 비즈니스 규칙 위반 → ErrorCode에 정의된 상태/코드로 변환. */
	@ExceptionHandler(BusinessException.class)
	public ProblemDetail handleBusiness(BusinessException e) {
		return problemOf(e.getErrorCode(), e.getMessage());
	}

	/** 로그인 실패 등 인증 예외 → 401. 어느 쪽이 틀렸는지 구분해 노출하지 않는다. */
	@ExceptionHandler(AuthenticationException.class)
	public ProblemDetail handleAuthentication(AuthenticationException e) {
		return problemOf(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.getMessage());
	}

	/** 잘못된 인자 → 400. */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
		return problemOf(ErrorCode.BAD_REQUEST, e.getMessage());
	}

	/** 어디서도 처리 못 한 예외 → 500. 원인은 로그로만 남기고 응답엔 내부 정보를 노출하지 않는다. */
	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		return problemOf(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
	}

	/** @Valid 검증 실패 → 400 + 어떤 필드가 왜 틀렸는지 fieldErrors. (프레임워크 훅을 오버라이드) */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
				.map(f -> Map.of(
						"field", f.getField(),
						"message", f.getDefaultMessage() == null ? "" : f.getDefaultMessage()))
				.toList();

		ProblemDetail problem = problemOf(ErrorCode.VALIDATION_FAILED, "요청 값 검증에 실패했습니다");
		problem.setProperty("fieldErrors", fieldErrors);

		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}
}
