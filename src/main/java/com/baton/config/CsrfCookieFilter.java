package com.baton.config;

import java.io.IOException;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CSRF 토큰을 매 요청마다 실제로 로드해 쿠키(XSRF-TOKEN)가 응답에 실리도록 강제한다.
 *
 * Spring Security 6+ 는 토큰을 지연(deferred) 로드하므로, 아무도 토큰을 읽지 않으면
 * 쿠키가 내려가지 않는다. 프론트는 GET 요청(예: /auth/me) 응답으로 이 쿠키를 받아야
 * 이후 상태변경 요청에 X-XSRF-TOKEN 헤더를 실을 수 있다.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
		if (csrfToken != null) {
			// getToken() 호출이 지연 토큰을 실제로 로드 → CookieCsrfTokenRepository가 쿠키를 응답에 쓴다.
			csrfToken.getToken();
		}
		filterChain.doFilter(request, response);
	}
}
