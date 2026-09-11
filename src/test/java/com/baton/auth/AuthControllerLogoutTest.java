package com.baton.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/**
 * 로그아웃 시 세션 무효화 회귀 테스트.
 *
 * 로그아웃했는데 서버 세션이 안 지워지면, 로그아웃 전에 유출된 세션 쿠키로 계속 접근할 수 있다(조용한 취약점).
 * AuthController.logout은 SecurityContextLogoutHandler로 세션을 invalidate하며, Spring Session JDBC에선
 * 이게 곧 SPRING_SESSION 행 삭제다. "로그아웃 후 그 옛 세션 쿠키로는 인증이 안 된다(401)"를 코드로 못박아 둔다.
 *
 * 실제 HTTP + Spring Session JDBC 경로를 태워야 하므로 RANDOM_PORT로 띄운다.
 * 로컬 Postgres(docker compose up -d)가 떠 있어야 한다. OpenAI/S3 키는 컨텍스트 로딩용 더미값.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.ai.openai.api-key=test-dummy",
				"app.s3.bucket=test-dummy-bucket"
		})
class AuthControllerLogoutTest {

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository userRepository;

	private final HttpClient http = HttpClient.newHttpClient();
	private final Map<String, String> cookies = new HashMap<>();

	private final String email = "logout-" + UUID.randomUUID() + "@test.local";
	private final String password = "Test1234!";

	@AfterEach
	void cleanup() {
		userRepository.findByEmail(email).ifPresent(userRepository::delete);
	}

	@Test
	void 로그아웃하면_기존_세션쿠키로는_인증되지_않는다() throws Exception {
		// 0) CSRF 토큰 확보
		send("GET", "/api/v1/auth/me", null, null);

		// 1) 계정 생성 + 로그인
		send("POST", "/api/v1/auth/signup", """
				{"email":"%s","password":"%s","name":"로그아웃테스트","team":"QA","position":"tester"}
				""".formatted(email, password), null);
		HttpResponse<String> login = send("POST", "/api/v1/auth/login",
				"""
				{"email":"%s","password":"%s"}
				""".formatted(email, password), null);
		assertThat(login.statusCode()).isEqualTo(200);

		// 로그아웃 후 재사용할 "그 시점의 세션 쿠키" 값을 확보해 둔다(로그아웃이 jar의 쿠키를 지워도 원본으로 재현).
		String stolenSession = cookies.get("BATON_SESSION");
		assertThat(stolenSession).as("로그인 세션 쿠키").isNotBlank();

		// 2) 로그인 상태에선 /me 가 200
		HttpResponse<String> meBefore = send("GET", "/api/v1/auth/me", null, null);
		assertThat(meBefore.statusCode()).as("로그아웃 전 /me").isEqualTo(200);

		// 3) 로그아웃 → 204
		HttpResponse<String> logout = send("POST", "/api/v1/auth/logout", null, null);
		assertThat(logout.statusCode()).as("로그아웃").isEqualTo(204);

		// 4) 유출됐다 치는 "옛 세션 쿠키"로 /me 재시도 → 세션이 죽었으니 401 이어야 한다.
		HttpResponse<String> meAfter = send("GET", "/api/v1/auth/me", null, "BATON_SESSION=" + stolenSession);
		assertThat(meAfter.statusCode()).as("로그아웃 후 옛 세션 쿠키로 접근").isEqualTo(401);
	}

	/**
	 * 쿠키 jar를 유지하며 요청하고 응답 Set-Cookie를 반영한다. CSRF 토큰은 헤더로 자동 첨부.
	 * cookieOverride가 있으면 jar 대신 그 값을 Cookie 헤더로 보낸다(옛 세션 쿠키 재사용 시나리오용).
	 */
	private HttpResponse<String> send(String method, String path, String jsonBody, String cookieOverride)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path));

		if (jsonBody != null) {
			request.header("Content-Type", "application/json");
			request.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
		} else {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		}

		if (cookieOverride != null) {
			request.header("Cookie", cookieOverride);
		} else if (!cookies.isEmpty()) {
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
