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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 회원가입 / 로그인 / 로그아웃 / 내 정보.
 * 로그인에 성공하면 SecurityContext를 세션에 저장하고, 서버는 HTTP-only 세션 쿠키를 내려준다.
 * 이후 요청은 프론트가 쿠키를 자동 전송(fetch credentials: 'include')하면 인증된다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final AuthenticationManager authenticationManager;

	// 인증 결과를 세션(JDBC 저장)에 넣어 쿠키와 연결한다. 상태가 없어 필드 초기화로 충분.
	private final SecurityContextRepository securityContextRepository =
			new HttpSessionSecurityContextRepository();

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse signup(@Valid @RequestBody SignupRequest req) {
		return UserResponse.from(authService.signup(req.email(), req.password(), req.name()));
	}

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

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest request, HttpServletResponse response) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		// 세션 무효화 + SecurityContext 정리
		new SecurityContextLogoutHandler().logout(request, response, auth);
	}

	@GetMapping("/me")
	public UserResponse me(Authentication authentication) {
		return UserResponse.from(authService.getByEmail(authentication.getName()));
	}
}
