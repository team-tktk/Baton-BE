package com.baton.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 프론트(다른 포트/도메인)에서 이 API를 호출할 수 있게 허용한다.
 * 이게 없으면 브라우저가 "CORS policy" 에러를 내며 요청을 막는다.
 * 허용 주소는 application.yml 의 app.cors.allowed-origins 에서 관리.
 *
 * CorsConfigurationSource 빈으로 노출하는 이유: Spring Security 필터가 MVC보다 먼저 도므로,
 * SecurityConfig의 .cors(withDefaults())가 이 빈을 찾아 preflight(OPTIONS)까지 통과시킨다.
 * allowCredentials(true) → 세션 쿠키가 교차 출처 요청에 실려 인증이 유지된다.
 */
@Configuration
public class CorsConfig {

	@Value("${app.cors.allowed-origins}")
	private List<String> allowedOrigins;

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		// allowCredentials(true)와 함께 "*"는 금지되므로, 정확 매칭 대신 패턴 매칭을 쓴다.
		// 패턴은 정확한 origin(http://localhost:5173)도 그대로 허용하고, 와일드카드(*.vercel.app 등)도 받는다.
		config.setAllowedOriginPatterns(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}
}
