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
 * 세션 고정(Session Fixation) 방어 회귀 테스트.
 *
 * AuthController.login은 Security 로그인 필터가 아니라 컨트롤러에서 수동으로 인증을 처리하므로,
 * 세션 ID 재발급(ChangeSessionIdAuthenticationStrategy)이 자동으로 걸리지 않는다. 이를 수동으로
 * 끼워넣었는데, 누군가 그 한 줄을 지워도 로그인은 멀쩡히 되고 화면상 티가 안 나 조용히 취약해진다.
 * 그래서 "이미 세션이 있는 상태에서 로그인하면 세션 ID가 새 값으로 바뀐다"를 코드로 못박아 둔다.
 *
 * 실제 HTTP + Spring Session JDBC 경로를 그대로 태워야 세션 ID 교체가 검증되므로 RANDOM_PORT로 띄운다.
 * 로컬 Postgres(docker compose up -d)가 떠 있어야 한다. OpenAI/S3 키는 컨텍스트 로딩용 더미값.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.ai.openai.api-key=test-dummy",
				"app.s3.bucket=test-dummy-bucket"
		})
class AuthControllerSessionFixationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository userRepository;

	private final HttpClient http = HttpClient.newHttpClient();

	// 요청 간 유지되는 쿠키 jar(BATON_SESSION, XSRF-TOKEN 등). 브라우저의 쿠키 저장소를 흉내낸다.
	private final Map<String, String> cookies = new HashMap<>();

	private final String email = "sessfix-" + UUID.randomUUID() + "@test.local";
	private final String password = "Test1234!";

	@AfterEach
	void cleanup() {
		userRepository.findByEmail(email).ifPresent(userRepository::delete);
	}

	@Test
	void 로그인하면_세션ID가_새로_발급된다() throws Exception {
		// 0) CSRF 토큰 확보 — 아무 GET이나 하면 XSRF-TOKEN 쿠키가 내려온다(미인증이라 401이어도 무방).
		send("GET", "/api/v1/auth/me", null);

		// 1) 테스트 계정 생성
		String signupBody = """
				{"email":"%s","password":"%s","name":"세션테스트","team":"QA","position":"tester"}
				""".formatted(email, password);
		HttpResponse<String> signup = send("POST", "/api/v1/auth/signup", signupBody);
		assertThat(signup.statusCode()).isEqualTo(201);

		String loginBody = """
				{"email":"%s","password":"%s"}
				""".formatted(email, password);

		// 2) 1차 로그인 → 세션 A 발급
		HttpResponse<String> login1 = send("POST", "/api/v1/auth/login", loginBody);
		assertThat(login1.statusCode()).isEqualTo(200);
		String sessionA = cookies.get("BATON_SESSION");

		// 3) 세션 A를 그대로 들고 2차 로그인 → 세션 B (여기서 ID가 바뀌어야 세션 고정 방어)
		HttpResponse<String> login2 = send("POST", "/api/v1/auth/login", loginBody);
		assertThat(login2.statusCode()).isEqualTo(200);
		String sessionB = cookies.get("BATON_SESSION");

		// 검증: 로그인 시 세션 ID가 새 값으로 교체됨
		assertThat(sessionA).as("1차 로그인 세션 ID").isNotBlank();
		assertThat(sessionB).as("2차 로그인 세션 ID").isNotBlank();
		assertThat(sessionB).as("로그인 시 세션 ID가 재발급되어야 한다(세션 고정 방어)").isNotEqualTo(sessionA);
	}

	/**
	 * 쿠키 jar를 유지하며 요청하고, 응답 Set-Cookie를 jar에 반영한다.
	 * CSRF: 보관 중인 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 반사해 상태변경 요청을 통과시킨다.
	 */
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
		}
		String xsrf = cookies.get("XSRF-TOKEN");
		if (xsrf != null) {
			request.header("X-XSRF-TOKEN", xsrf);
		}

		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());

		List<String> setCookies = response.headers().allValues("Set-Cookie");
		for (String setCookie : setCookies) {
			String pair = setCookie.split(";", 2)[0];   // "이름=값" 만, 속성(Path 등) 제거
			int eq = pair.indexOf('=');
			if (eq > 0) {
				cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
			}
		}
		return response;
	}
}
