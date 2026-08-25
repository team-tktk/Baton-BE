package com.baton.member;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.auth.User;
import com.baton.auth.UserRepository;
import com.baton.member.dto.MemberPageResponse;
import com.baton.member.dto.MemberResponse;

import lombok.RequiredArgsConstructor;

/**
 * 구성원 검색. 인수인계 인수자 선택 화면에서 이름/팀으로 사용자를 찾는다.
 * size+1건을 읽어 다음 페이지 존재 여부를 판별하는 키셋 방식.
 */
@Service
@RequiredArgsConstructor
public class MemberService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;

	private final UserRepository userRepository;

	@Transactional(readOnly = true)
	public MemberPageResponse search(String query, UUID cursor, int size) {
		int pageSize = clampSize(size);
		String q = (query == null || query.isBlank()) ? null : query.trim();

		// 다음 페이지 유무를 알기 위해 한 건 더 조회
		List<User> rows = userRepository.searchByKeyword(q, cursor, PageRequest.of(0, pageSize + 1));

		boolean hasNext = rows.size() > pageSize;
		List<User> page = hasNext ? rows.subList(0, pageSize) : rows;
		String nextCursor = hasNext ? page.get(page.size() - 1).getId().toString() : null;

		return new MemberPageResponse(page.stream().map(MemberResponse::from).toList(), nextCursor, hasNext);
	}

	private int clampSize(int size) {
		if (size <= 0) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}
}
