package com.baton.health;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 서버 상태 확인용. 나중에 지워도 됨.
 * 컨트롤러에 @Tag / @Operation 을 달면 Swagger 문서에 설명이 붙는다.
 */
@Tag(name = "Health", description = "서버 상태 확인")
@RestController
@RequestMapping("/api")
public class HealthController {

	@Operation(summary = "헬스 체크", description = "서버와 DB가 떠 있는지 확인한다.")
	@GetMapping("/health")
	public Map<String, Object> health() {
		return Map.of(
				"status", "UP",
				"service", "baton-be",
				"time", LocalDateTime.now().toString()
		);
	}
}
