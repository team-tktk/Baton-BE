package com.baton.auth;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baton.auth.dto.UserSummaryResponse;

import lombok.RequiredArgsConstructor;

/**
 * userId 집합을 한 번의 조회로 사용자 요약(이름·팀·직책)으로 바꿔주는 조회 헬퍼.
 * 목록·상세에서 참여자마다 개별 조회(N+1)하지 않도록 배치로 읽어 맵으로 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class UserDirectory {

	private final UserRepository userRepository;

	/** 여러 userId를 한 번에 요약으로. 조회되지 않은 id는 맵에 없으니 호출부에서 unknown 처리한다. */
	@Transactional(readOnly = true)
	public Map<UUID, UserSummaryResponse> summarize(Collection<UUID> ids) {
		if (ids == null || ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, UserSummaryResponse> result = new LinkedHashMap<>();
		userRepository.findAllById(ids)
				.forEach(user -> result.put(user.getId(), UserSummaryResponse.from(user)));
		return result;
	}

	/** 단건 요약. 없으면 unknown 기본값. */
	@Transactional(readOnly = true)
	public UserSummaryResponse summarize(UUID id) {
		return userRepository.findById(id)
				.map(UserSummaryResponse::from)
				.orElseGet(() -> UserSummaryResponse.unknown(id));
	}
}
