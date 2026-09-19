package com.baton.readiness.dto;

import java.util.List;

import com.baton.ai.dto.HandoverDraftContent;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** AI가 여러 부족 영역을 한 번에 보완한 결과(모델 출력 형식). */
public record GeneratedFix(
		@JsonPropertyDescription("고칠 수 있는 섹션 중 실제로 바꾼 필드만 '수정 후 전체 값'으로 채우고, 나머지 필드는 전부 null")
		HandoverDraftContent patch,
		@JsonPropertyDescription("보완할 영역마다 정확히 한 개씩의 결과")
		List<GeneratedAreaFix> areas) {
}
