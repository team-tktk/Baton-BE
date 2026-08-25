package com.baton.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	/** 회원가입. 이메일 중복이면 409로 이어진다. 비밀번호는 BCrypt로 해싱해 저장한다. */
	@Transactional
	public User signup(String email, String rawPassword, String name) {
		if (userRepository.existsByEmail(email)) {
			throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATE, "이미 사용 중인 이메일입니다: " + email);
		}
		User user = User.create(email, passwordEncoder.encode(rawPassword), name);
		return userRepository.save(user);
	}

	/** 로그인된 세션에서 현재 회원을 조회한다(이메일 = 인증 principal). */
	@Transactional(readOnly = true)
	public User getByEmail(String email) {
		return userRepository.findByEmail(email)
				.orElseThrow(() -> new IllegalStateException("인증된 사용자를 찾을 수 없습니다: " + email));
	}
}
