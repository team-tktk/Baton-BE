package com.baton.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import com.baton.common.RestAuthenticationEntryPoint;

/**
 * 인증/인가 설정. HTTP-only 쿠키 세션 방식.
 *
 * CSRF: 쿠키 기반 토큰(CookieCsrfTokenRepository, XSRF-TOKEN 쿠키)으로 방어한다.
 *       프론트는 GET 응답으로 받은 XSRF-TOKEN 쿠키 값을 상태변경 요청(POST/PUT/PATCH/DELETE)마다
 *       X-XSRF-TOKEN 헤더로 실어 보내야 한다. SameSite=Lax만으로는 못 막는 multipart 업로드까지 커버.
 *       - SpaCsrfTokenRequestHandler: 헤더로 온 원본 토큰을 검증(SPA용, BREACH 완화 XOR 병행)
 *       - CsrfCookieFilter: 매 요청 토큰을 로드해 쿠키가 응답에 실리도록 강제
 * CORS: CorsConfig의 CorsConfigurationSource 빈을 사용(자격증명 쿠키 전송 허용).
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
				.addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
				.authorizeHttpRequests(auth -> auth
						// 인증 없이 접근 가능한 공개 엔드포인트
						.requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
						.requestMatchers("/health", "/actuator/health").permitAll()
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
						// 그 외 모든 요청은 로그인 필요
						.anyRequest().authenticated())
				// 세션은 필요할 때 생성(로그인 시). Spring Session이 JDBC에 저장.
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				// 미인증 요청은 로그인 폼 리다이렉트 대신, 나머지 에러와 같은 ProblemDetail(401) 반환
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(new RestAuthenticationEntryPoint()))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable); // 로그아웃은 AuthController에서 직접 처리

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}
}
