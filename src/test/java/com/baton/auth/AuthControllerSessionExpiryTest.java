package com.baton.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 세션 만료(7일 비활성) 회귀 테스트.
 *
 * 만료 값은 SessionConfig의 @EnableJdbcHttpSession(maxInactiveIntervalInSeconds=7일)로 관리한다.
 * Boot 4는 세션 스토어 자동설정이 없어 application.yml의 timeout이 조용히 무시되므로, "7일이 실제로
 * 적용됐는지"와 "만료된 세션은 거부되는지"를 코드로 못박아 둔다.
 *
 * 실제 HTTP + Spring Session JDBC 경로를 태우고 DB를 직접 조작하므로 RANDOM_PORT + JdbcTemplate.
 * 로컬 Postgres(docker compose up -d)가 떠 있어야 한다. OpenAI/S3 키는 컨텍스트 로딩용 더미값.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.ai.openai.api-key=test-dummy",
				"app.s3.bucket=test-dummy-bucket"
		})
class AuthControllerSessionExpiryTest {

	private static final long SEVEN_DAYS_SECONDS = 7L * 24 * 60 * 60;

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private final HttpClient http = HttpClient.newHttpClient();
	private final Map<String, String> cookies = new HashMap<>();

	private final String email = "expiry-" + UUID.randomUUID() + "@test.local";
	private final String password = "Test1234!";

	@AfterEach
	void cleanup() {
		userRepository.findByEmail(email).ifPresent(userRepository::delete);
	}

	@Test
	void 세션은_7일로_설정되고_만료되면_거부된다() throws Exception {
		// 0) CSRF 토큰 확보 + 계정 생성 + 로그인
		send("GET", "/api/v1/auth/me", null);
		send("POST", "/api/v1/auth/signup", """
				{"email":"%s","password":"%s","name":"만료테스트","team":"QA","position":"tester"}
				""".formatted(email, password));
		HttpResponse<String> login = send("POST", "/api/v1/auth/login", """
				{"email":"%s","password":"%s"}
				""".formatted(email, password));
		assertThat(login.statusCode()).isEqualTo(200);

		// 쿠키 값(base64)을 디코드하면 DB의 session_id(UUID 문자열)가 된다.
		String sessionCookie = cookies.get("BATON_SESSION");
		assertThat(sessionCookie).as("로그인 세션 쿠키").isNotBlank();
		String sessionId = new String(Base64.getDecoder().decode(sessionCookie), StandardCharsets.UTF_8);

		// 1) 설정 검증 — 이 세션의 비활성 만료가 실제로 7일(604800초)로 저장됐는가
		Long maxInactive = jdbc.queryForObject(
				"SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
				Long.class, sessionId);
		assertThat(maxInactive).as("세션 비활성 만료(초)").isEqualTo(SEVEN_DAYS_SECONDS);

		// 로그인 직후엔 유효 → /me 200
		assertThat(send("GET", "/api/v1/auth/me", null).statusCode()).as("만료 전 /me").isEqualTo(200);

		// 2) enforcement 검증 — 마지막 접근 시각을 8일 전으로 조작해 강제 만료시킨다.
		long eightDaysAgo = System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000);
		jdbc.update("UPDATE spring_session SET last_access_time = ?, expiry_time = ? WHERE session_id = ?",
				eightDaysAgo, eightDaysAgo, sessionId);

		// 만료된 세션 쿠키로 접근 → 인증 거부(401)
		HttpResponse<String> meExpired = send("GET", "/api/v1/auth/me", null);
		assertThat(meExpired.statusCode()).as("만료 후 /me").isEqualTo(401);
	}

	/** 쿠키 jar를 유지하며 요청하고 응답 Set-Cookie를 반영한다. CSRF 토큰은 헤더로 자동 첨부. */
	private HttpResponse<String> send(String method, String path, String jsonBody) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path));

		if (jsonBody != null) {
			request.header("Content-Type", "application/json");
			request.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
		} else {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		}
		if (!cookies.isEmpty()) {
			request.header("Cookie", cookies.entrySet().stream()
					.map(e -> e.getKey() + "=" + e.getValue())
					.collect(Collectors.joining("; ")));
			String xsrf = cookies.get("XSRF-TOKEN");
			if (xsrf != null) {
				request.header("X-XSRF-TOKEN", xsrf);
			}
		}

		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());

		List<String> setCookies = response.headers().allValues("Set-Cookie");
		for (String setCookie : setCookies) {
			String pair = setCookie.split(";", 2)[0];
			int eq = pair.indexOf('=');
			if (eq > 0) {
				cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
			}
		}
		return response;
	}
}
