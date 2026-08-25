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

import com.baton.common.RestAuthenticationEntryPoint;

/**
 * 인증/인가 설정. HTTP-only 쿠키 세션 방식.
 *
 * CSRF: SameSite=Lax(application.yml) + REST 규칙(변경은 POST/PUT/PATCH/DELETE)으로 방어하므로 비활성.
 *       나중에 강화하려면 csrf를 CookieCsrfTokenRepository로 켜고 프론트에 X-XSRF-TOKEN 헤더를 추가하면 됨.
 * CORS: CorsConfig의 CorsConfigurationSource 빈을 사용(자격증명 쿠키 전송 허용).
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.cors(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
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
