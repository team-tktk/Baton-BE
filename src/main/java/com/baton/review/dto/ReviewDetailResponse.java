package com.baton.review.dto;

import java.util.List;
import java.util.UUID;

import com.baton.ai.dto.FileMetadataResponse;
import com.baton.ai.dto.HandoverDraftResponse;

/**
 * 관리자 검토 상세. 검토 화면 한 번에 필요한 문서 초안·첨부·체크리스트·코멘트를 함께 내린다.
 * document는 아직 초안이 없으면 null.
 */
public record ReviewDetailResponse(
		UUID handoverId,
		String status,
		HandoverDraftResponse document,
		List<FileMetadataResponse> attachments,
		List<ChecklistItemResponse> checklist,
		List<CommentResponse> comments) {
}
