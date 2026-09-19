package com.baton.ai;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.baton.ai.dto.CreateSlackMessageRequest;
import com.baton.ai.dto.CreateWebLinkRequest;
import com.baton.ai.dto.ExternalSourceResponse;
import com.baton.ai.dto.UpdateExternalSourceRequest;
import com.baton.handover.HandoverAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "04. 외부 자료", description = "웹 링크와 Slack 메시지를 인수인계 AI 자료로 등록·관리합니다.")
@RestController
@RequestMapping("/api/v1/handovers/{handoverId}/sources")
@RequiredArgsConstructor
public class ExternalSourceController {
	private final ExternalSourceService service;
	private final HandoverAccess handoverAccess;

	@Operation(summary = "웹 링크 등록", description = "공개 HTML은 안전하게 가져와 본문을 추출합니다. 로그인 필요 링크는 설명을 본문으로 사용합니다.")
	@PostMapping("/web-links")
	@ResponseStatus(HttpStatus.CREATED)
	public ExternalSourceResponse createWeb(@PathVariable UUID handoverId,
			@Valid @RequestBody CreateWebLinkRequest request, Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		ExternalSourceService.Result result = service.createWeb(handoverId, request);
		return ExternalSourceResponse.from(result.source(), result.fetched());
	}

	@Operation(summary = "Slack 메시지 등록", description = "OAuth 없이 메시지 링크·채널명·붙여넣은 본문을 저장합니다.")
	@PostMapping("/slack-messages")
	@ResponseStatus(HttpStatus.CREATED)
	public ExternalSourceResponse createSlack(@PathVariable UUID handoverId,
			@Valid @RequestBody CreateSlackMessageRequest request, Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		ExternalSourceService.Result result = service.createSlack(handoverId, request);
		return ExternalSourceResponse.from(result.source(), result.fetched());
	}

	@Operation(summary = "웹/Slack 자료 수정", description = "웹 링크는 다시 가져오며, Slack 수정은 content를 함께 보내야 합니다. 변경 후 마스킹·인덱싱을 다시 수행합니다.")
	@PatchMapping("/{sourceId}")
	public ExternalSourceResponse update(@PathVariable UUID handoverId, @PathVariable UUID sourceId,
			@Valid @RequestBody UpdateExternalSourceRequest request, Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		ExternalSourceService.Result result = service.update(handoverId, sourceId, request);
		return ExternalSourceResponse.from(result.source(), result.fetched());
	}

	@Operation(summary = "웹/Slack 자료 재처리")
	@PostMapping("/{sourceId}/retry")
	public ExternalSourceResponse retry(@PathVariable UUID handoverId, @PathVariable UUID sourceId,
			Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		return ExternalSourceResponse.from(service.retry(handoverId, sourceId), true);
	}

	@Operation(summary = "웹/Slack 자료 삭제")
	@DeleteMapping("/{sourceId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID handoverId, @PathVariable UUID sourceId,
			Authentication authentication) {
		handoverAccess.requireOwner(handoverId, authentication);
		service.delete(handoverId, sourceId);
	}
}
