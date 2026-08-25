package com.baton.common;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 로그인 안 한 채 보호된 API에 접근했을 때의 401 응답.
 * 스프링 시큐리티 필터 단계라 컨트롤러 advice가 못 잡으므로 여기서 직접 응답을 쓴다.
 *
 * 주의: ProblemDetail 객체를 그냥 ObjectMapper로 직렬화하면 code가 properties 안에 중첩되고
 * type:null이 붙어서 advice가 내는 응답과 모양이 달라진다. 그래서 MVC가 내보내는 평탄한 형태
 * (title, status, detail, instance, code)를 직접 맞춰서 쓴다.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", ErrorCode.AUTH_REQUIRED.getMessage());
		body.put("status", HttpStatus.UNAUTHORIZED.value());
		body.put("detail", ErrorCode.AUTH_REQUIRED.getMessage());
		body.put("instance", request.getRequestURI());
		body.put("code", ErrorCode.AUTH_REQUIRED.name());

		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), body);
	}
}
