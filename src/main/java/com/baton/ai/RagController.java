package com.baton.ai;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.baton.ai.dto.DownloadedFile;
import com.baton.ai.dto.FileMetadataResponse;
import com.baton.ai.dto.FileUploadResponse;
import com.baton.ai.dto.HandoverDraftResponse;
import com.baton.ai.dto.QuestionAnswerRequest;
import com.baton.auth.AuthService;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverPermission;
import com.baton.handover.HandoverRepository;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 인증만 확인하고 소유권/열람권은 확인 안 하던 상태를 HandoverPermission으로 막는다.
 * 업로드/분석/질문 관리는 인계자(owner) 전용, 조회와 Q&A는 참여자(owner/recipient/reviewer) 전체 허용.
 *
 * TODO: Handover.status를 ANALYZING/ANSWERING/EDITING으로 전이시키는 건 handover 도메인과
 * 상태 전이 방식을 맞춰야 해서 별도로 처리한다. 지금은 requireOwner까지만 검사한다.
 */
@RestController
@RequestMapping("/api/v1/handovers/{handoverId}")
@RequiredArgsConstructor
public class RagController {

	private final RagIngestService ragIngestService;
	private final RagQueryService ragQueryService;
	private final RagAnalysisService ragAnalysisService;
	private final HandoverRepository handoverRepository;
	private final HandoverPermission handoverPermission;
	private final AuthService authService;

	@Operation(summary = "인수인계 파일 업로드", description = "파일을 텍스트로 추출해 벡터스토어에 인덱싱한다. 인계자만 가능.")
	@PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public FileUploadResponse uploadFile(
			@PathVariable UUID handoverId,
			@RequestPart("file") MultipartFile file,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		SourceDocument sourceDocument = ragIngestService.ingest(handoverId, file);
		return FileUploadResponse.from(sourceDocument);
	}

	@Operation(summary = "업로드된 파일 목록 조회", description = "참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/files")
	public List<FileMetadataResponse> listFiles(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragIngestService.listByHandover(handoverId).stream()
				.map(FileMetadataResponse::from)
				.toList();
	}

	@Operation(summary = "업로드된 파일 원본 다운로드", description = "참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/files/{fileId}/download")
	public ResponseEntity<byte[]> downloadFile(
			@PathVariable UUID handoverId,
			@PathVariable UUID fileId,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		DownloadedFile file = ragIngestService.download(handoverId, fileId);
		ContentDisposition contentDisposition = ContentDisposition.attachment()
				.filename(file.fileName(), StandardCharsets.UTF_8)
				.build();

		return ResponseEntity.ok()
				.contentType(file.mimeType() != null
						? MediaType.parseMediaType(file.mimeType())
						: MediaType.APPLICATION_OCTET_STREAM)
				.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
				.body(file.content());
	}

	@Operation(summary = "업로드된 파일 삭제", description = "인계자만 가능.")
	@DeleteMapping("/files/{fileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteFile(
			@PathVariable UUID handoverId,
			@PathVariable UUID fileId,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		ragIngestService.delete(handoverId, fileId);
	}

	@Operation(summary = "인수인계 문서 기반 질의응답", description = "업로드된 문서 안에서 근거를 찾아 답변한다. 참여자(인계자/인수자/관리자) 모두 가능.")
	@PostMapping("/chat/messages")
	public ChatAnswerResponse ask(
			@PathVariable UUID handoverId,
			@Valid @RequestBody ChatQuestionRequest request,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragQueryService.answer(handoverId, request.question());
	}

	@Operation(summary = "AI 인수인계 초안 생성", description = "업로드된 문서를 분석해 구조화된 초안과 확인 질문을 생성한다. 인계자만 가능.")
	@PostMapping("/analysis")
	public HandoverDraftResponse analyze(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.analyze(handoverId);
	}

	@Operation(summary = "인수인계 초안 조회", description = "참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/document")
	public HandoverDraftResponse getDraft(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragAnalysisService.getDraft(handoverId);
	}

	@Operation(summary = "AI 확인 질문 목록 조회", description = "인계자만 가능.")
	@GetMapping("/questions")
	public List<ClarificationQuestionResponse> getQuestions(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.getQuestions(handoverId);
	}

	@Operation(summary = "AI 확인 질문에 답변", description = "선택/직접 입력 답변을 저장하거나 건너뛴다. 인계자만 가능.")
	@PutMapping("/questions/{questionId}/answer")
	public ClarificationQuestionResponse answerQuestion(
			@PathVariable UUID handoverId,
			@PathVariable UUID questionId,
			@RequestBody QuestionAnswerRequest request,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.answerQuestion(handoverId, questionId, request);
	}

	@Operation(summary = "확인 질문 완료 처리", description = "답변된 질문 내용을 반영해 초안을 다시 생성한다. 인계자만 가능.")
	@PostMapping("/questions/complete")
	public HandoverDraftResponse completeQuestions(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.completeQuestions(handoverId);
	}

	private Handover loadHandover(UUID handoverId) {
		return handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
	}

	/** 세션 인증 principal(이메일)로 현재 사용자 id를 조회한다. */
	private UUID currentUserId(Authentication authentication) {
		return authService.getByEmail(authentication.getName()).getId();
	}
}
