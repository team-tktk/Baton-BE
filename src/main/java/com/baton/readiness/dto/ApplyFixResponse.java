package com.baton.readiness.dto;

import com.baton.ai.dto.HandoverDraftResponse;

/**
 * 보완안 적용 결과.
 *
 * @param readiness 적용 후 다시 평가한 준비도. 재평가가 실패하면 null — 문서 적용은 이미 끝났으니
 *                  POST /readiness/evaluate로 다시 평가하면 된다.
 */
public record ApplyFixResponse(
		ReadinessFixResponse fix,
		HandoverDraftResponse document,
		ReadinessResponse readiness) {
}
