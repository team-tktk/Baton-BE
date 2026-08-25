package com.baton.config;

import java.util.function.Supplier;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SPA(자바스크립트 프론트)용 CSRF 토큰 처리기. Spring Security 공식 가이드의 SPA 패턴.
 *
 * - 쿠키에 실어 내려줄 때는 XOR 마스킹(BREACH 공격 완화)을 적용한다.
 * - 프론트가 쿠키 값을 읽어 X-XSRF-TOKEN 헤더로 그대로 보내면, 그 원본 값으로 검증한다.
 *   (헤더가 있으면 원본 그대로 resolve, 없으면 XOR 디코드 경로 사용)
 */
final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

	private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
		this.delegate.handle(request, response, csrfToken);
	}

	@Override
	public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
		// 프론트가 헤더로 보낸 경우: 쿠키의 원본 토큰 값을 그대로 쓴다.
		if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
			return super.resolveCsrfTokenValue(request, csrfToken);
		}
		// 폼 파라미터로 온 경우: XOR 마스킹된 값을 디코드한다.
		return this.delegate.resolveCsrfTokenValue(request, csrfToken);
	}
}
