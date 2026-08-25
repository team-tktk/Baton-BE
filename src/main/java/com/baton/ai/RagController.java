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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.baton.ai.dto.ChatAnswerResponse;
import com.baton.ai.dto.ChatQuestionRequest;
import com.baton.ai.dto.AnalysisJobResponse;
import com.baton.ai.dto.ClarificationQuestionResponse;
import com.baton.ai.dto.DownloadedFile;
import com.baton.ai.dto.FileMetadataResponse;
import com.baton.ai.dto.FileUploadResponse;
import com.baton.ai.dto.ChatMessageResponse;
import com.baton.ai.dto.HandoverDraftResponse;
import com.baton.ai.dto.QuestionAnswerRequest;
import com.baton.ai.dto.UpdateDraftRequest;
import com.baton.ai.dto.SourceEvidenceResponse;
import com.baton.auth.AuthService;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverPermission;
import com.baton.handover.HandoverRepository;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증만 확인하고 소유권/열람권은 확인 안 하던 상태를 HandoverPermission으로 막는다.
 * 업로드/분석/질문 관리는 인계자(owner) 전용, 조회와 Q&A는 참여자(owner/recipient/reviewer) 전체 허용.
 *
 * 클래스 전체에 @Transactional을 건 이유: Handover.participants가 지연 로딩(LAZY)이라
 * HandoverPermission.requireViewer()가 owner가 아닌 참여자를 검사할 때 그 컬렉션을 읽는다.
 * open-in-view가 꺼져 있어서 트랜잭션 밖에서 읽으면 LazyInitializationException이 난다.
 * 분석 작업은 AnalysisJobService가 Handover 상태와 함께 전이한다.
 */
@RestController
@RequestMapping("/api/v1/handovers/{handoverId}")
@RequiredArgsConstructor
@Transactional
public class RagController {

	private final RagIngestService ragIngestService;
	private final RagQueryService ragQueryService;
	private final RagAnalysisService ragAnalysisService;
	private final AnalysisJobService analysisJobService;
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
		UUID userId = currentUserId(authentication);
		handoverPermission.requireViewer(handover, userId);

		return ragQueryService.answer(handoverId, userId, request.question());
	}

	@Operation(summary = "AI 대화 이력 조회", description = "참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/chat/messages")
	public List<ChatMessageResponse> listMessages(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragQueryService.listMessages(handoverId);
	}

	@Operation(summary = "AI 인수인계 초안 생성", description = "업로드된 문서를 분석해 구조화된 초안과 확인 질문을 생성한다. 인계자만 가능.")
	@PostMapping("/analysis")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AnalysisJobResponse analyze(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return analysisJobService.start(handoverId, false);
	}

	@Operation(summary = "AI 분석 작업 상태 조회", description = "가장 최근 분석 작업의 상태와 진행률을 조회한다. 인계자만 가능.")
	@GetMapping("/analysis")
	public AnalysisJobResponse getAnalysis(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));
		return analysisJobService.getLatest(handoverId);
	}

	@Operation(summary = "AI 분석 작업 재시도", description = "가장 최근 작업이 실패한 경우 새 분석 작업을 생성한다. 인계자만 가능.")
	@PostMapping("/analysis/retry")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AnalysisJobResponse retryAnalysis(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));
		return analysisJobService.start(handoverId, true);
	}

	@Operation(summary = "인수인계 초안 조회", description = "참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/document")
	public HandoverDraftResponse getDraft(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragAnalysisService.getDraft(handoverId);
	}

	@Operation(summary = "인수인계 초안 수정", description = "사람이 직접 고친 내용을 저장한다(자동저장). 인계자만 가능.")
	@PatchMapping("/document")
	public HandoverDraftResponse updateDraft(
			@PathVariable UUID handoverId,
			@Valid @RequestBody UpdateDraftRequest request,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.updateDraft(handoverId, request.content());
	}

	@Operation(summary = "인수자 첫날 요약", description = "AI 초안과 같은 내용을 인수자 관점 요약 화면용으로 제공한다. 참여자 모두 가능.")
	@GetMapping("/briefing")
	public HandoverDraftResponse getBriefing(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragAnalysisService.getDraft(handoverId);
	}

	@Operation(summary = "인수인계 문서 Markdown 내보내기", description = "참여자(인계자/인수자/관리자) 모두 가능.")
	@GetMapping("/document/export")
	public ResponseEntity<byte[]> exportDraft(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		String markdown = ragAnalysisService.exportMarkdown(handoverId, handover.getTitle());
		ContentDisposition contentDisposition = ContentDisposition.attachment()
				.filename(handover.getTitle() + ".md", StandardCharsets.UTF_8)
				.build();

		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
				.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
				.body(markdown.getBytes(StandardCharsets.UTF_8));
	}

	@Operation(summary = "AI 확인 질문 목록 조회", description = "인계자만 가능.")
	@GetMapping("/questions")
	public List<ClarificationQuestionResponse> getQuestions(
			@PathVariable UUID handoverId,
			@RequestParam(required = false) ClarificationQuestionType type,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.getQuestions(handoverId, type);
	}

	@Operation(summary = "AI 확인 질문에 답변", description = "선택/직접 입력 답변을 저장하거나 건너뛴다. 인계자만 가능.")
	@PutMapping("/questions/{questionId}/answer")
	public ClarificationQuestionResponse answerQuestion(
			@PathVariable UUID handoverId,
			@PathVariable UUID questionId,
			@Valid @RequestBody QuestionAnswerRequest request,
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

	@Operation(summary = "AI 원문 근거 목록", description = "AI 분석과 답변에 사용되는 원문 목록과 접근 경로를 조회한다.")
	@GetMapping("/sources")
	public List<SourceEvidenceResponse> getSources(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragIngestService.listByHandover(handoverId).stream()
				.map(source -> SourceEvidenceResponse.from(handoverId, source))
				.toList();
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
