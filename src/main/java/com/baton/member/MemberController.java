package com.baton.member;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baton.member.dto.MemberPageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 구성원 검색 API. 로그인 사용자가 인수인계 인수자를 고를 때 이름/팀으로 검색한다.
 */
@Tag(name = "02. 구성원", description = "인수자/관리자 지정을 위한 구성원 검색.")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@Operation(summary = "구성원 검색",
			description = """
					이름 또는 팀으로 부분검색(대소문자 무시)한다. 인수인계 생성 시 인수자/관리자를 고르는 데 쓴다.
					`query`가 비면 전체를 커서 순으로 반환한다. 결과 항목의 `id`를 인수인계의 `recipientIds`/`reviewerIds`에 사용한다.
					""")
	@GetMapping
	public MemberPageResponse search(
			@Parameter(description = "검색어(이름/팀 부분일치, 대소문자 무시). 생략 시 전체.")
			@RequestParam(required = false) String query,
			@Parameter(description = "직전 페이지 마지막 구성원 id. 첫 페이지는 생략.")
			@RequestParam(required = false) UUID cursor,
			@Parameter(description = "페이지 크기(기본 20, 최대 100).")
			@RequestParam(required = false, defaultValue = "20") int size) {
		return memberService.search(query, cursor, size);
	}
}
