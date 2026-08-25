package com.baton.member;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baton.member.dto.MemberPageResponse;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/**
 * 구성원 검색 API. 로그인 사용자가 인수인계 인수자를 고를 때 이름/팀으로 검색한다.
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@Operation(summary = "구성원 검색", description = "이름/팀 부분검색(대소문자 무시) + 커서 페이지네이션. 인수자 선택용.")
	@GetMapping
	public MemberPageResponse search(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) UUID cursor,
			@RequestParam(required = false, defaultValue = "20") int size) {
		return memberService.search(query, cursor, size);
	}
}
