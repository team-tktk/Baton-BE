package com.baton.ai;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.baton.ai.dto.ChatMessagePageResponse;
import com.baton.ai.dto.ChatQuestionRequest;
import com.baton.ai.dto.AnalysisJobResponse;
import com.baton.ai.dto.ClarificationQuestionResponse;
import com.baton.ai.dto.DownloadedFile;
import com.baton.ai.dto.FileMetadataResponse;
import com.baton.ai.dto.FileUploadResponse;
import com.baton.ai.dto.ChatMessageResponse;
import com.baton.ai.dto.HandoverBriefingResponse;
import com.baton.ai.dto.HandoverDraftResponse;
import com.baton.ai.dto.QuestionAnswerRequest;
import com.baton.ai.dto.UpdateDraftRequest;
import com.baton.ai.dto.SourceDetailResponse;
import com.baton.ai.dto.SourceEvidenceResponse;
import com.baton.aiusage.AiFeature;
import com.baton.aiusage.AiTask;
import com.baton.aiusage.AiTaskLockService;
import com.baton.aiusage.AiUsageGuard;
import com.baton.auth.AuthService;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverAccess;
import com.baton.handover.HandoverPermission;
import com.baton.handover.HandoverRepository;
import com.baton.masking.MaskingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
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
@Tag(name = "04. 파일 · AI 분석 · 문서",
		description = "첨부 파일 업로드/다운로드, 비동기 AI 분석과 확인 질문, 구조화된 문서 초안, 인수자 질의응답까지. "
				+ "업로드·분석·질문·문서 수정은 인계자 전용, 조회와 Q&A는 참여자 전체 허용.")
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
	private final MaskingService maskingService;
	private final AiUsageGuard aiUsageGuard;
	private final AiTaskLockService aiTaskLockService;
	private final HandoverAccess handoverAccess;

	@Operation(summary = "인수인계 파일 업로드",
			description = """
					multipart/form-data(파트명 file)로 업로드하면 원본을 S3에 저장하고 텍스트를 추출해 벡터스토어에 인덱싱한다. 인계자만 가능.

					- 허용 확장자: pdf, docx, xlsx, pptx(대소문자 무시, 확장자로 1차 판별).
					- 확장자만으로 끝내지 않고 Content-Type과 파일 실제 내용(매직바이트)까지 확인한다.
					  확장자를 위장한 실행파일이나 내용이 다른 파일은 거절된다.
					- 파일명은 저장 전에 경로 구분자·상위 디렉토리 참조(..)·제어문자를 제거해서 저장한다.
					- 파일당 최대 **50MB**(초과 시 413).
					- 인수인계 1건당 파일 최대 **30개**, 누적 용량 최대 **300MB**까지 업로드 가능.
					- 계정(인계자) 전체 기준 누적 용량은 최대 **1GB**까지(파일 삭제 시 다시 풀림).
					- 응답 FileUploadResponse: sourceDocumentId(=파일 목록의 id, 근거의 sourceId/fileId와 동일)·fileName·status.
					- 처리 상태(status): 업로드 직후 EXTRACTING → 성공 시 INDEXED, 실패 시 FAILED(재처리 가능).
					  마스킹 검수가 켜진 서버에서는 성공 시 MASKING_REVIEW(검수 대기)로 멈춘다.
					- 지원하지 않는 형식/내용 불일치/실행파일 감지: 400(code=AI_UNSUPPORTED_FILE_TYPE)
					- 개수/용량 상한 초과: 400(code=AI_UPLOAD_QUOTA_EXCEEDED)
					- 빈 파일: 400(code=BAD_REQUEST) / 텍스트 추출 실패: 422(code=AI_FILE_PARSE_FAILED, 상태 FAILED)
					""")
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

	@Operation(summary = "업로드된 파일 목록 조회",
			description = """
					인수인계에 첨부된 파일 메타데이터(FileMetadataResponse) 배열을 반환한다. 참여자 모두 가능.
					각 항목: id(=업로드 응답의 sourceDocumentId, 근거의 sourceId/fileId와 동일)·fileName·mimeType·size(바이트)·status·remainingReviewCount·createdAt.
					remainingReviewCount: 마스킹 검수에서 아직 확인하지 않은 항목 수(검수 대기 파일이 아니면 0).
					status: EXTRACTING(처리 중)·MASKING_REVIEW(마스킹 검수 대기)·INDEXING(임베딩 중)·INDEXED(완료)·FAILED(실패).
					""")
	@GetMapping("/files")
	public List<FileMetadataResponse> listFiles(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		Map<UUID, Long> remainingReviewCounts = maskingService.countPendingReviewByFile(handoverId);
		return ragIngestService.listByHandover(handoverId).stream()
				.map(file -> FileMetadataResponse.from(file, remainingReviewCounts.getOrDefault(file.getId(), 0L)))
				.toList();
	}

	@Operation(summary = "업로드된 파일 원본 다운로드",
			description = """
					S3에 저장된 원본 파일을 바이트로 내려준다. 인수자/관리자가 첨부 원문(또는 근거 citations[].fileId)을 열 때 쓴다. 참여자 모두 가능.
					- Content-Type: 저장된 MIME(없으면 application/octet-stream).
					- Content-Disposition: attachment; filename&#42;=UTF-8''&lt;파일명&gt;(원본 파일명, UTF-8 인코딩).
					- 없는 파일: 404(code=AI_SOURCE_DOCUMENT_NOT_FOUND)
					""")
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

	@Operation(summary = "업로드된 파일 삭제",
			description = """
					첨부 파일을 삭제한다(S3 원본·메타데이터·벡터스토어 인덱스). 인계자만 가능. 성공: 204 No Content.
					- 삭제 가능 상태: MASKING_REVIEW·INDEXED·FAILED. 처리 중(EXTRACTING·INDEXING) 파일은 삭제 불가: 409(code=AI_SOURCE_DOCUMENT_PROCESSING).
					- 없는 파일: 404(code=AI_SOURCE_DOCUMENT_NOT_FOUND)
					""")
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

	@Operation(summary = "파일 추출/임베딩 재처리",
			description = """
					텍스트 추출·인덱싱에 실패(FAILED)한 파일을 S3 원본으로 다시 처리한다. 인계자만 가능. 새 처리 상태를 반환한다.
					- 마스킹 검수를 확정한 뒤 인덱싱에 실패한 파일은 원문을 다시 추출하지 않고, 마스킹된 텍스트로 인덱싱만 다시 한다.
					- 실패 상태가 아닌 파일 재처리: 409(code=HANDOVER_INVALID_STATE)
					""")
	@PostMapping("/files/{fileId}/retry")
	public FileUploadResponse retryFile(
			@PathVariable UUID handoverId,
			@PathVariable UUID fileId,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		SourceDocument sourceDocument = ragIngestService.retry(handoverId, fileId);
		return FileUploadResponse.from(sourceDocument);
	}

	@Operation(summary = "AI 답변 근거 원문 단건 조회",
			description = """
					AI 답변의 citations[].sourceId를 눌렀을 때 해당 원문 메타데이터(제목·파일 id·수정일 등)를 보여준다. 참여자 모두 가능.
					- 없는 근거: 404(code=AI_SOURCE_DOCUMENT_NOT_FOUND)
					""")
	@GetMapping("/sources/{sourceId}")
	public SourceDetailResponse getSourceDetail(
			@PathVariable UUID handoverId,
			@PathVariable UUID sourceId,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return SourceDetailResponse.from(ragIngestService.getSource(handoverId, sourceId));
	}

	@Operation(summary = "채팅 추천 질문",
			description = """
					채팅창에서 바로 눌러볼 수 있는 추천 질문 목록. 인수인계 초안 내용에 근거해 AI가 만들고,
					초안이 바뀌기 전까지는 캐싱된 결과를 그대로 재사용한다. 참여자 모두 가능.
					""")
	@GetMapping("/chat/suggested-questions")
	public List<String> getSuggestedQuestions(
			@PathVariable UUID handoverId,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragAnalysisService.getSuggestedQuestions(handoverId);
	}

	@Operation(summary = "인수인계 문서 기반 질의응답",
			description = """
					업로드된 문서 안에서 근거(citation)를 찾아 답변한다(RAG). 주로 인수자가 첫날 궁금증을 물을 때 쓴다. 참여자 모두 가능.
					answerSource로 답변 출처를 구분한다:
					- DOCUMENT: 문서에서 근거를 찾음 → grounded=true, answer, citations[] 포함
					- GENERAL_KNOWLEDGE: 문서에 근거는 없지만 AI가 상황에 맞게 스스로 판단해서 답함
					  → grounded=false, answer 있음, citations=[], fallbackContact=null. 세 가지 경우가 여기 섞여 있다:
					    1) 일반적으로 알려진 용어/개념(예: "ROI가 뭐야?") → 그 뜻을 바로 설명
					    2) 질문이 애매해서 특정이 안 됨 → AI가 되묻는 질문을 answer로 반환(예: "어떤 배송업체를 말씀하시는 건가요?")
					    3) 회사만 아는 고유 정보라 되물어도 답할 수 없음 → "팀장님/인계자에게 직접 문의하라"는 안내를 answer로 반환
					  프론트는 이 경우 "사내 자료 기준 답변이 아님"을 표시해주는 게 좋다. answer 자체가 이미 자연어 안내문이라
					  별도 UI 분기 없이 그대로 보여줘도 된다.
					- NOT_FOUND: 위 판단 자체가 기술적으로 실패했을 때만 쓰는 최후 수단(사실상 드묾)
					  → grounded=false, answer=null, fallbackContact(문의 대상 안내) 포함.

					citations[].sourceId == fileId == 업로드 파일(SourceDocument) id로 항상 같은 값이다.
					원문 메타데이터는 GET /sources/{sourceId}, 원본 파일 다운로드는 GET /files/{fileId}/download로 잇는다.
					locator는 문서 내 대략 위치(청크 순번). title은 파일명.

					- AI 요청 한도 초과(인수인계서 생성·보완안 생성·채팅 합산): 429(code=AI_USAGE_LIMIT_EXCEEDED, Retry-After 헤더·retryAt 포함)
					""",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = ChatQuestionRequest.class),
					examples = {
						@ExampleObject(name = "기본", value = """
								{ "question": "배송업체가 회신을 안 하면 언제까지 기다려요?" }
								""")
					})),
			responses = @ApiResponse(responseCode = "200", description = "성공", content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = ChatAnswerResponse.class),
					examples = {
						@ExampleObject(name = "문서에서 근거를 찾은 경우", value = """
								{
								  "messageId": "b2c3d4e5-...",
								  "answer": "오늘 오후 3시까지 답이 없으면 물류팀에 공유하세요.",
								  "grounded": true,
								  "answerSource": "DOCUMENT",
								  "citations": [
								    { "sourceId": "a1b2c3d4-...", "title": "문제상황_대응방법.pdf", "locator": "청크 3/12", "fileId": "a1b2c3d4-...", "updatedAt": "2026-08-21T09:00:00Z" }
								  ],
								  "fallbackContact": null,
								  "answeredAt": "2026-08-25T02:00:00Z"
								}
								"""),
						@ExampleObject(name = "문서엔 없지만 일반적으로 알려진 용어라 일반 지식으로 답한 경우", value = """
								{
								  "messageId": "d4e5f6a7-...",
								  "answer": "ROI는 투자 대비 수익률(Return On Investment)을 뜻해요.",
								  "grounded": false,
								  "answerSource": "GENERAL_KNOWLEDGE",
								  "citations": [],
								  "fallbackContact": null,
								  "answeredAt": "2026-08-25T02:00:00Z"
								}
								"""),
						@ExampleObject(name = "질문이 애매해서 AI가 되물은 경우", value = """
								{
								  "messageId": "e5f6a7b8-...",
								  "answer": "어떤 배송업체를 말씀하시는 건가요? 알려주시면 다시 찾아볼게요.",
								  "grounded": false,
								  "answerSource": "GENERAL_KNOWLEDGE",
								  "citations": [],
								  "fallbackContact": null,
								  "answeredAt": "2026-08-25T02:00:00Z"
								}
								"""),
						@ExampleObject(name = "회사 고유 정보라 팀장님/인계자에게 문의하라고 안내한 경우", value = """
								{
								  "messageId": "f6a7b8c9-...",
								  "answer": "이 부분은 자료에 없어서 제가 확인해드리기 어려워요. 팀장님이나 인계자분께 직접 여쭤보시는 게 좋을 것 같아요.",
								  "grounded": false,
								  "answerSource": "GENERAL_KNOWLEDGE",
								  "citations": [],
								  "fallbackContact": null,
								  "answeredAt": "2026-08-25T02:00:00Z"
								}
								"""),
						@ExampleObject(name = "판단 호출 자체가 실패해 정말 아무 답도 못 만든 경우(드묾)", value = """
								{
								  "messageId": "c3d4e5f6-...",
								  "answer": null,
								  "grounded": false,
								  "answerSource": "NOT_FOUND",
								  "citations": [],
								  "fallbackContact": "업로드된 문서에서 답을 찾지 못했습니다. 인계자에게 직접 문의해주세요.",
								  "answeredAt": "2026-08-25T02:00:00Z"
								}
								""")
					})))
	@PostMapping("/chat/messages")
	@Transactional(propagation = Propagation.NOT_SUPPORTED) // AI 응답을 기다리는 동안 커넥션을 두 개 쥐지 않게
	public ChatAnswerResponse ask(
			@PathVariable UUID handoverId,
			@Valid @RequestBody ChatQuestionRequest request,
			Authentication authentication) {
		UUID userId = handoverAccess.requireViewer(handoverId, authentication);
		aiUsageGuard.acquire(userId, AiFeature.CHAT, handoverId);

		return ragQueryService.answer(handoverId, userId, request.question());
	}

	@Operation(summary = "AI 대화 이력 조회",
			description = "인수인계별 사용자/AI 메시지와 인용 근거를 시간순으로 반환한다. 참여자 모두 가능. "
					+ "커서 페이지네이션: cursor는 직전 응답의 nextCursor(ISO-8601)를 그대로 전달, size 기본 20.")
	@GetMapping("/chat/messages")
	public ChatMessagePageResponse listMessages(
			@PathVariable UUID handoverId,
			@Parameter(description = "직전 응답의 nextCursor(ISO-8601 시각). 첫 페이지는 생략.")
			@RequestParam(required = false) String cursor,
			@Parameter(description = "페이지 크기(기본 20).")
			@RequestParam(required = false, defaultValue = "20") int size,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		Instant cursorInstant = (cursor == null || cursor.isBlank()) ? null : Instant.parse(cursor);
		return ragQueryService.listMessages(handoverId, cursorInstant, size);
	}

	@Operation(summary = "AI 분석·초안 생성 시작",
			description = """
					업로드된 문서를 분석해 구조화된 초안과 확인 질문을 만드는 **비동기 작업**을 시작한다(→ 상태 ANALYZING). 인계자만 가능.
					즉시 202 Accepted로 작업 정보를 반환하고, 진행률은 GET /analysis로 폴링한다.
					- 분석할 업로드 파일이 없음: 400(code=AI_NO_DOCUMENTS)
					- 이미 진행 중인 작업: 409(code=AI_ANALYSIS_ALREADY_RUNNING)
					- 마스킹 검수를 확정하지 않은 파일(MASKING_REVIEW·INDEXING)이 있음: 409(code=MASKING_NOT_CONFIRMED)
					""")
	@PostMapping("/analysis")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AnalysisJobResponse analyze(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return analysisJobService.start(handoverId, false);
	}

	@Operation(summary = "AI 분석 작업 상태 조회(폴링)",
			description = """
					가장 최근 분석 작업의 상태·진행률·현재 단계를 조회한다. **권장 폴링 주기 2~3초.** 인계자만 가능.

					응답 AnalysisJobResponse: jobId·status·progress(0~100 정수)·currentStep(사람이 읽는 현재 단계 문구)·error(실패 시 사유, 아니면 null)·updatedAt.
					- 진행 중: QUEUED·PARSING·INDEXING·GENERATING_QUESTIONS·GENERATING_DRAFT
					- **완료 상태**: COMPLETED — 폴링 종료. 확인 질문이 있으면 GET /questions가 채워지고(본 상태 ANSWERING),
					  없으면 빈 배열(본 상태 EDITING)이니 바로 GET /document로 초안을 조회하면 된다.
					- **실패 상태**: FAILED — error에 사유. POST /analysis/retry로만 재시도 가능(재시도 가능한 유일한 실패).
					- 작업 없음: 404(code=AI_ANALYSIS_JOB_NOT_FOUND)
					""")
	@GetMapping("/analysis")
	public AnalysisJobResponse getAnalysis(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));
		return analysisJobService.getLatest(handoverId);
	}

	@Operation(summary = "AI 분석 작업 재시도",
			description = """
					가장 최근 작업이 실패(FAILED)한 경우에만 새 분석 작업을 만든다. 인계자만 가능.
					- 실패 상태가 아닐 때 재시도: 409(code=AI_ANALYSIS_RETRY_NOT_ALLOWED)
					""")
	@PostMapping("/analysis/retry")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AnalysisJobResponse retryAnalysis(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));
		return analysisJobService.start(handoverId, true);
	}

	@Operation(summary = "인수인계 문서(초안) 조회",
			description = """
					AI가 생성했거나 사람이 수정한 구조화된 문서(섹션·업무·기준·관계자·일정·체크리스트 등)를 반환한다. 참여자 모두 가능.
					- 아직 생성된 초안 없음: 404(code=AI_DRAFT_NOT_FOUND)
					""")
	@GetMapping("/document")
	public HandoverDraftResponse getDraft(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragAnalysisService.getDraft(handoverId);
	}

	@Operation(summary = "인수인계 문서(초안) 수정",
			description = """
					사람이 직접 고친 문서 내용을 저장한다(자동저장). 인계자만 가능. 현재는 content 전체를 통째로 교체한다.
					- baseRevision(선택): 조회 응답의 revision을 그대로 보내면, 그사이 다른 창·준비도 보완 적용으로 문서가 바뀌었을 때
					  저장을 거절한다 → 409(code=AI_DRAFT_REVISION_CONFLICT). 생략하면 기존처럼 마지막 저장이 이긴다.
					- 응답의 revision은 저장 후 새 버전이다.
					- 인계자가 아니면: 403(code=HANDOVER_FORBIDDEN)
					""",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = UpdateDraftRequest.class),
					examples = {
						@ExampleObject(name = "기본", value = """
								{
								  "content": {
								    "purpose": "가을 정기 할인전을 기획·운영해 분기 매출 목표를 달성한다.",
								    "completionCriteria": "후임자가 쿠폰 발행부터 정산까지 단독으로 진행할 수 있다.",
								    "ongoingTasks": [
								      { "title": "가을 할인전 쿠폰 세팅", "status": "진행 중", "description": "10% 쿠폰을 운영 어드민에 등록 중.", "nextAction": "마케팅 확인 후 팀장 승인", "schedule": "9/30까지" }
								    ],
								    "recurringTasks": [
								      { "title": "주간 주문 현황 공유", "status": "매주 반복", "description": "반품·문의 포함 집계", "nextAction": "월요일 오전 집계", "schedule": "매주 월요일" }
								    ],
								    "rulesAndExceptions": ["쿠폰 승인은 마케팅 확인 후 팀장 승인", "배송업체 미회신 시 오후 3시까지 대기 후 물류팀 공유"],
								    "stakeholders": [ { "name": "김미영", "team": "마케팅팀", "helpWith": "쿠폰 정책 확인" } ],
								    "tools": [ { "name": "주간 주문 현황 양식.xlsx", "description": "매주 주문·반품 기록" } ],
								    "schedule": [ { "cycle": "매주 월요일", "task": "주문 현황 집계", "detail": "반품·문의 포함 공유" } ],
								    "accessAccounts": [ { "tool": "운영 어드민", "permission": "주문 조회·행사 설정", "status": "사용 가능" } ],
								    "firstWeekChecklist": ["운영 어드민 계정 발급 확인", "쿠폰 승인 라인 파악"],
								    "confirmedCriteria": [ { "label": "쿠폰 승인", "value": "마케팅 확인 후 팀장 승인" } ]
								  },
								  "baseRevision": 3
								}
								""")
					})))
	@PatchMapping("/document")
	public HandoverDraftResponse updateDraft(
			@PathVariable UUID handoverId,
			@Valid @RequestBody UpdateDraftRequest request,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.updateDraft(handoverId, request.content(), request.baseRevision());
	}

	@Operation(summary = "인수자 첫날 요약(브리핑)",
			description = """
					AI가 초안을 바탕으로 쓴 환영 브리핑과, 당장 필요한 항목(첫 주 체크리스트·접근권한·주요 관계자)만 추려서 제공한다. 참여자 모두 가능.
					브리핑 문장은 초안이 바뀌기 전까지 캐시해 재사용한다.
					""")
	@GetMapping("/briefing")
	public HandoverBriefingResponse getBriefing(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragAnalysisService.getBriefing(handoverId);
	}

	@Operation(summary = "인수인계 문서 Markdown 내보내기",
			description = "서버에 저장된 문서를 .md 파일로 내려준다(text/markdown, Content-Disposition: attachment). 참여자 모두 가능.")
	@GetMapping("/document/export")
	public ResponseEntity<byte[]> exportDraft(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		String markdown = ragAnalysisService.exportMarkdown(handover);
		ContentDisposition contentDisposition = ContentDisposition.attachment()
				.filename(handover.getTitle() + ".md", StandardCharsets.UTF_8)
				.build();

		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
				.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
				.body(markdown.getBytes(StandardCharsets.UTF_8));
	}

	@Operation(summary = "AI 확인 질문 목록 조회",
			description = """
					AI가 초안 보완을 위해 만든 확인 질문(질문·이유·선택지·근거·반영 위치)을 중요도순으로 반환한다. 인계자만 가능.
					type=INTERVIEW(추가 정보 인터뷰) 또는 type=CONFLICT(문서 간 충돌 해소)로 필터할 수 있다.
					- 한 번의 분석에서 새로 만드는 질문은 **최대 5개**다. 자료에 답이 있는 질문·이미 물어본 질문과 같은 뜻의 질문은 만들지 않는다.
					- reason: 질문 이유. targetSections: 답변이 반영될 문서 위치(section·field=문서 JSON 필드명·label=섹션 이름).
					- priority: 중요도 순위(1이 가장 중요). applied: 현재 상태가 문서에 반영됐는지.
					- status: PENDING(미응답)·ANSWERED(답변)·UNKNOWN(모름)·NOT_APPLICABLE(해당 없음)·DEFERRED(나중에 답하기).
					**질문이 0개면 빈 배열**을 반환한다 — 이땐 답변 단계를 건너뛰고 바로 GET /document로 초안을 조회하면 된다.
					""")
	@GetMapping("/questions")
	public List<ClarificationQuestionResponse> getQuestions(
			@PathVariable UUID handoverId,
			@Parameter(description = "질문 유형 필터: INTERVIEW · CONFLICT. 생략 시 전체.")
			@RequestParam(required = false) ClarificationQuestionType type,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.getQuestions(handoverId, type);
	}

	@Operation(summary = "AI 확인 질문 처리(답변·모름·해당 없음·나중에 답하기)",
			description = """
					질문 하나의 처리 상태를 저장/수정한다. 인계자만 가능. 문서는 바로 바뀌지 않는다 — 완료 처리 또는 POST /questions/apply 때 반영된다.
					상태를 바꾸거나 답변을 고치면 applied=false로 돌아가 다시 반영 대상이 된다.

					status별 의미와 규칙(validCombination 검증):
					- ANSWERED: 답변. answer 필수(공백만은 불가). 문서에 확정 내용으로 반영된다.
					- UNKNOWN: 인계자도 모름. answer 없이. 값을 추측해 채우지 않고, 첫 주 체크리스트에 "확인 필요" 항목으로 남긴다.
					- NOT_APPLICABLE: 이 업무엔 해당 없음. answer 없이. 해당 항목을 문서에 만들지 않는다.
					- DEFERRED: 나중에 답하기. answer 없이. 완료 처리를 막지 않는다. 인수인계 준비도(GET /readiness의 deferredQuestions)에 표시되고(점수에는 영향 없음), 나중에 답한 뒤 POST /questions/apply로 반영한다.
					- PENDING으로 되돌리기, 규칙 위반: 400(code=VALIDATION_FAILED)
					- 없는 질문: 404(code=AI_QUESTION_NOT_FOUND)
					""",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
					mediaType = "application/json",
					schema = @Schema(implementation = QuestionAnswerRequest.class),
					examples = {
						@ExampleObject(name = "답변", value = """
								{ "status": "ANSWERED", "answer": "마케팅 확인 후 팀장 승인" }
								"""),
						@ExampleObject(name = "모름", value = """
								{ "status": "UNKNOWN" }
								"""),
						@ExampleObject(name = "해당 없음", value = """
								{ "status": "NOT_APPLICABLE" }
								"""),
						@ExampleObject(name = "나중에 답하기", value = """
								{ "status": "DEFERRED" }
								""")
					})))
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

	@Operation(summary = "확인 질문 완료 처리",
			description = """
					질문 처리 결과를 문서에 반영하고 최신 문서를 반환한다(본 상태 → EDITING). 인계자만 가능.
					- 문서가 아직 없으면(첫 완료): 자료 + 처리 결과로 문서 전체를 생성한다.
					- 문서가 이미 있으면(재분석 후 완료): 아직 반영되지 않은 질문의 targetSections만 갱신하고, 나머지 섹션(사람이 고친 내용 포함)은 그대로 둔다.

					PENDING이 하나도 없으면 호출 가능하다. DEFERRED(나중에 답하기)는 완료를 막지 않는다.
					- 확인 질문 단계(ANSWERING)가 아님(이미 완료한 뒤 다시 호출 등): 409(code=HANDOVER_INVALID_STATE)
					  → 완료 뒤 답한 질문은 POST /questions/apply로 반영한다.
					- 아직 PENDING 질문이 남아 있음: 409(code=AI_QUESTIONS_INCOMPLETE)
					- 같은 인수인계의 인수인계서 생성이 이미 진행 중(중복 클릭 등): 409(code=AI_TASK_ALREADY_RUNNING)
					- AI 요청 한도 초과(인수인계서 생성·보완안 생성·채팅 합산): 429(code=AI_USAGE_LIMIT_EXCEEDED)
					""")
	@PostMapping("/questions/complete")
	@Transactional(propagation = Propagation.NOT_SUPPORTED) // AI 응답을 기다리는 동안 커넥션을 쥐지 않게
	public HandoverDraftResponse completeQuestions(@PathVariable UUID handoverId, Authentication authentication) {
		UUID userId = handoverAccess.requireOwner(handoverId, authentication);

		return aiTaskLockService.runExclusive(AiTask.DRAFT_GENERATION, handoverId, () ->
				ragAnalysisService.completeQuestions(handoverId,
						() -> aiUsageGuard.acquire(userId, AiFeature.DRAFT_GENERATION, handoverId)));
	}

	@Operation(summary = "확인 질문 처리 결과를 문서에 반영",
			description = """
					문서가 만들어진 뒤 처리한 질문(예: 나중에 답하기였다가 답한 질문)을 문서에 반영하고 최신 문서를 반환한다. 인계자만 가능.
					문서 전체를 다시 만들지 않고, applied=false인 질문(ANSWERED·UNKNOWN·NOT_APPLICABLE)의 targetSections만 갱신한다.
					반영할 질문이 없으면 AI 호출 없이 현재 문서를 그대로 반환한다(사용량도 차감하지 않는다).
					- 아직 문서가 없음: 404(code=AI_DRAFT_NOT_FOUND) — 이땐 POST /questions/complete로 문서를 먼저 만든다.
					- 같은 인수인계의 인수인계서 생성이 이미 진행 중(중복 클릭 등): 409(code=AI_TASK_ALREADY_RUNNING)
					- AI 요청 한도 초과(인수인계서 생성·보완안 생성·채팅 합산): 429(code=AI_USAGE_LIMIT_EXCEEDED)
					""")
	@PostMapping("/questions/apply")
	@Transactional(propagation = Propagation.NOT_SUPPORTED) // AI 응답을 기다리는 동안 커넥션을 쥐지 않게
	public HandoverDraftResponse applyAnswers(@PathVariable UUID handoverId, Authentication authentication) {
		UUID userId = handoverAccess.requireOwner(handoverId, authentication);

		return aiTaskLockService.runExclusive(AiTask.DRAFT_GENERATION, handoverId, () ->
				ragAnalysisService.applyAnswers(handoverId,
						() -> aiUsageGuard.acquire(userId, AiFeature.DRAFT_GENERATION, handoverId)));
	}

	@Operation(summary = "AI 원문 근거 목록",
			description = "AI 분석·답변에 참조되는 원문(문서 제목·위치·수정일·접근 경로) 목록을 반환한다. 단건 상세는 GET /sources/{sourceId}. 참여자 모두 가능.")
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
