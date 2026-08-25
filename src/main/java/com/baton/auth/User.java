package com.baton.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원. 비밀번호는 절대 평문으로 저장하지 않고 BCrypt 해시(passwordHash)만 보관한다.
 * 테이블명이 "users"인 이유: "user"는 PostgreSQL 예약어라 그대로 쓰면 깨진다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용 기본 생성자
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private User(String email, String passwordHash, String name) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.name = name;
	}

	/** 새 회원 생성. passwordHash는 반드시 해시된 값이어야 한다(평문 금지). */
	public static User create(String email, String passwordHash, String name) {
		return new User(email, passwordHash, name);
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}
}
