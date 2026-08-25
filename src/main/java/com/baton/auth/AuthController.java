package com.baton.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.baton.auth.dto.LoginRequest;
import com.baton.auth.dto.SignupRequest;
import com.baton.auth.dto.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 회원가입 / 로그인 / 로그아웃 / 내 정보.
 * 로그인에 성공하면 SecurityContext를 세션에 저장하고, 서버는 HTTP-only 세션 쿠키를 내려준다.
 * 이후 요청은 프론트가 쿠키를 자동 전송(fetch credentials: 'include')하면 인증된다.
 */
@Tag(name = "01. 인증", description = "회원가입·로그인·로그아웃·내 정보. 로그인 성공 시 HTTP-only 세션 쿠키가 발급되며, 이후 요청은 쿠키로 인증된다.")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final AuthenticationManager authenticationManager;

	// 인증 결과를 세션(JDBC 저장)에 넣어 쿠키와 연결한다. 상태가 없어 필드 초기화로 충분.
	private final SecurityContextRepository securityContextRepository =
			new HttpSessionSecurityContextRepository();

	@Operation(summary = "회원가입",
			description = """
					이메일·비밀번호·이름·팀·직책으로 회원을 등록하고 생성된 프로필을 반환한다. 비로그인 상태로 호출한다.
					- 성공: `201 Created`
					- 이메일 중복: `409`(code=`AUTH_EMAIL_DUPLICATE`)
					- 입력 검증 실패: `400`(code=`VALIDATION_FAILED`, `fieldErrors`에 항목별 사유)
					""")
	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse signup(@Valid @RequestBody SignupRequest req) {
		return UserResponse.from(
				authService.signup(req.email(), req.password(), req.name(), req.team(), req.position()));
	}

	@Operation(summary = "로그인",
			description = """
					이메일·비밀번호로 인증하고 HTTP-only 세션 쿠키를 발급한다(응답 `Set-Cookie`).
					프론트는 이후 모든 요청에 `credentials: 'include'`로 쿠키를 실어 보내야 인증된다.
					- 성공: `200 OK` + 사용자 프로필
					- 자격 증명 불일치: `401`(code=`AUTH_INVALID_CREDENTIALS`)
					""")
	@PostMapping("/login")
	public UserResponse login(@Valid @RequestBody LoginRequest req,
			HttpServletRequest request, HttpServletResponse response) {
		// 자격 증명 검증 — 실패 시 BadCredentialsException → 401
		Authentication authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(req.email(), req.password()));

		// 인증 성공 → SecurityContext를 세션에 저장(= 로그인 상태 유지)
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);

		return UserResponse.from(authService.getByEmail(authentication.getName()));
	}

	@Operation(summary = "로그아웃",
			description = "현재 세션을 무효화하고 SecurityContext를 정리한다. 이미 로그아웃 상태여도 안전하다. 성공: `204 No Content`.")
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest request, HttpServletResponse response) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		// 세션 무효화 + SecurityContext 정리
		new SecurityContextLogoutHandler().logout(request, response, auth);
	}

	@Operation(summary = "내 정보 조회",
			description = """
					로그인한 사용자의 프로필(id·이메일·이름·팀·직책)을 반환한다. 앱 진입 시 세션 유효성 확인용으로 호출한다.
					- 미인증: `401`(code=`AUTH_REQUIRED`)
					""")
	@GetMapping("/me")
	public UserResponse me(Authentication authentication) {
		return UserResponse.from(authService.getByEmail(authentication.getName()));
	}
}
