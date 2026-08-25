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

	/** 소속 팀. 지금은 자유입력 문자열(예: "모아스토어 · 운영팀"). 공유·관리가 필요해지면 Team 엔티티로 분리. */
	@Column(nullable = false)
	private String team;

	/**
	 * 직책(예: "팀장", "과장"). 인수인계 승인자 지정 시 적임자를 식별하기 위한 표시용 속성.
	 * 권한 자체는 아니며(전역 역할 X), 승인 권한은 handover별 REVIEWER 관계로 판단한다.
	 * 컬럼명은 예약어 회피를 위해 job_title. 기존 행 호환을 위해 nullable.
	 */
	@Column(name = "job_title")
	private String position;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private User(String email, String passwordHash, String name, String team, String position) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.name = name;
		this.team = team;
		this.position = position;
	}

	/** 새 회원 생성. passwordHash는 반드시 해시된 값이어야 한다(평문 금지). */
	public static User create(String email, String passwordHash, String name, String team, String position) {
		return new User(email, passwordHash, name, team, position);
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}
}
