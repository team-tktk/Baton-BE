package com.baton.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.baton.ai.dto.ChatAnswerResponse;
import com.baton.ai.dto.ChatQuestionRequest;
import com.baton.ai.dto.ClarificationQuestionResponse;
import com.baton.ai.dto.FileUploadResponse;
import com.baton.ai.dto.HandoverDraftResponse;
import com.baton.ai.dto.QuestionAnswerRequest;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/handovers/{handoverId}")
@RequiredArgsConstructor
public class RagController {

	private final RagIngestService ragIngestService;
	private final RagQueryService ragQueryService;
	private final RagAnalysisService ragAnalysisService;

	@Operation(summary = "인수인계 파일 업로드", description = "파일을 텍스트로 추출해 벡터스토어에 인덱싱한다.")
	@PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public FileUploadResponse uploadFile(
			@PathVariable UUID handoverId,
			@RequestPart("file") MultipartFile file) {
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

	@Operation(summary = "AI 인수인계 초안 생성", description = "업로드된 문서를 분석해 구조화된 초안과 확인 질문을 생성한다.")
	@PostMapping("/analysis")
	public HandoverDraftResponse analyze(@PathVariable UUID handoverId) {
		return ragAnalysisService.analyze(handoverId);
	}

	@Operation(summary = "인수인계 초안 조회")
	@GetMapping("/document")
	public HandoverDraftResponse getDraft(@PathVariable UUID handoverId) {
		return ragAnalysisService.getDraft(handoverId);
	}

	@Operation(summary = "AI 확인 질문 목록 조회")
	@GetMapping("/questions")
	public List<ClarificationQuestionResponse> getQuestions(@PathVariable UUID handoverId) {
		return ragAnalysisService.getQuestions(handoverId);
	}

	@Operation(summary = "AI 확인 질문에 답변", description = "선택/직접 입력 답변을 저장하거나 건너뛴다.")
	@PutMapping("/questions/{questionId}/answer")
	public ClarificationQuestionResponse answerQuestion(
			@PathVariable UUID handoverId,
			@PathVariable UUID questionId,
			@RequestBody QuestionAnswerRequest request) {
		return ragAnalysisService.answerQuestion(handoverId, questionId, request);
	}

	@Operation(summary = "확인 질문 완료 처리", description = "답변된 질문 내용을 반영해 초안을 다시 생성한다.")
	@PostMapping("/questions/complete")
	public HandoverDraftResponse completeQuestions(@PathVariable UUID handoverId) {
		return ragAnalysisService.completeQuestions(handoverId);
	}
}
