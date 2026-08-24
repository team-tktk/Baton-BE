package com.baton.common;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 컨트롤러에서 터진 예외를 한 곳에서 JSON 응답으로 변환한다.
 * 이게 없으면 500 에러에 스택트레이스가 그대로 노출되고,
 * 프론트는 무엇이 잘못됐는지 알 수 없다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** @Valid 검증 실패 → 400 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
		String detail = e.getBindingResult().getFieldErrors().stream()
				.map(f -> f.getField() + ": " + f.getDefaultMessage())
				.collect(Collectors.joining(", "));

		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problem.setTitle("입력값이 올바르지 않습니다");
		problem.setDetail(detail);
		return problem;
	}

	/** 잘못된 인자 → 400 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problem.setTitle("잘못된 요청");
		problem.setDetail(e.getMessage());
		return problem;
	}
}
