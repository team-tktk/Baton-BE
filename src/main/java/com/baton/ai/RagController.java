package com.baton.ai;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.baton.ai.dto.ChatAnswerResponse;
import com.baton.ai.dto.ChatQuestionRequest;
import com.baton.ai.dto.FileUploadResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/handovers/{handoverId}")
@RequiredArgsConstructor
public class RagController {

	private final RagIngestService ragIngestService;
	private final RagQueryService ragQueryService;

	@Operation(summary = "인수인계 파일 업로드", description = "파일을 텍스트로 추출해 벡터스토어에 인덱싱한다.")
	@PostMapping("/files")
	@ResponseStatus(HttpStatus.CREATED)
	public FileUploadResponse uploadFile(
			@PathVariable UUID handoverId,
			@RequestParam("file") MultipartFile file) {
		SourceDocument sourceDocument = ragIngestService.ingest(handoverId, file);
		return FileUploadResponse.from(sourceDocument);
	}

	@Operation(summary = "인수인계 문서 기반 질의응답", description = "업로드된 문서 안에서 근거를 찾아 답변한다.")
	@PostMapping("/chat/messages")
	public ChatAnswerResponse ask(
			@PathVariable UUID handoverId,
			@Valid @RequestBody ChatQuestionRequest request) {
		return ragQueryService.answer(handoverId, request.question());
	}
}
