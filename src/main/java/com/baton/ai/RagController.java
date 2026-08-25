package com.baton.ai;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import com.baton.auth.AuthService;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverPermission;
import com.baton.handover.HandoverRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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

	@Operation(summary = "인수인계 파일 업로드",
			description = """
					`multipart/form-data`(파트명 `file`)로 업로드하면 원본을 S3에 저장하고 텍스트를 추출해 벡터스토어에 인덱싱한다. 인계자만 가능.

					- 허용 확장자: `pdf`, `docx`, `xlsx`, `pptx`(대소문자 무시, 확장자로 판별). MIME은 저장만 하고 검증 기준은 아니다.
					- 파일당 최대 **50MB**(초과 시 `413`). 인수인계당 파일 개수 상한은 없다.
					- 응답 `FileUploadResponse`: `sourceDocumentId`(=파일 목록의 `id`, 근거의 `sourceId`/`fileId`와 동일)·`fileName`·`status`.
					- 처리 상태(`status`): 업로드 직후 `EXTRACTING` → 성공 시 `INDEXED`, 실패 시 `FAILED`(재처리 가능).
					- 지원하지 않는 형식: `400`(code=`AI_UNSUPPORTED_FILE_TYPE`)
					- 빈 파일: `400`(code=`BAD_REQUEST`) / 텍스트 추출 실패: `422`(code=`AI_FILE_PARSE_FAILED`, 상태 `FAILED`)
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
					인수인계에 첨부된 파일 메타데이터(`FileMetadataResponse`) 배열을 반환한다. 참여자 모두 가능.
					각 항목: `id`(=업로드 응답의 `sourceDocumentId`, 근거의 `sourceId`/`fileId`와 동일)·`fileName`·`mimeType`·`size`(바이트)·`status`·`createdAt`.
					`status`: `EXTRACTING`(처리 중)·`INDEXED`(완료)·`FAILED`(실패).
					""")
	@GetMapping("/files")
	public List<FileMetadataResponse> listFiles(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragIngestService.listByHandover(handoverId).stream()
				.map(FileMetadataResponse::from)
				.toList();
	}

	@Operation(summary = "업로드된 파일 원본 다운로드",
			description = """
					S3에 저장된 원본 파일을 바이트로 내려준다. 인수자/관리자가 첨부 원문(또는 근거 `citations[].fileId`)을 열 때 쓴다. 참여자 모두 가능.
					- `Content-Type`: 저장된 MIME(없으면 `application/octet-stream`).
					- `Content-Disposition`: `attachment; filename*=UTF-8''<파일명>`(원본 파일명, UTF-8 인코딩).
					- 없는 파일: `404`(code=`AI_SOURCE_DOCUMENT_NOT_FOUND`)
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
					첨부 파일을 삭제한다(S3 원본·메타데이터·벡터스토어 인덱스). 인계자만 가능. 성공: `204 No Content`.
					- 삭제 가능 상태: `INDEXED` 또는 `FAILED`. 처리 중(`EXTRACTING`) 파일은 삭제 불가: `409`(code=`AI_SOURCE_DOCUMENT_PROCESSING`).
					- 없는 파일: `404`(code=`AI_SOURCE_DOCUMENT_NOT_FOUND`)
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
					텍스트 추출·인덱싱에 실패(`FAILED`)한 파일을 S3 원본으로 다시 처리한다. 인계자만 가능. 새 처리 상태를 반환한다.
					- 실패 상태가 아닌 파일 재처리: `409`(code=`HANDOVER_INVALID_STATE`)
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
					AI 답변의 `citations[].sourceId`를 눌렀을 때 해당 원문 메타데이터(제목·파일 id·수정일 등)를 보여준다. 참여자 모두 가능.
					- 없는 근거: `404`(code=`AI_SOURCE_DOCUMENT_NOT_FOUND`)
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

	@Operation(summary = "인수인계 문서 기반 질의응답",
			description = """
					업로드된 문서 안에서 근거(citation)를 찾아 답변한다(RAG). 주로 인수자가 첫날 궁금증을 물을 때 쓴다. 참여자 모두 가능.
					자료에서 답을 찾으면 `grounded=true`, `answer`, `citations[]`를 준다. 못 찾으면 임의로 지어내지 않고
					`grounded=false`, `answer=null`, `fallbackContact`(문의 대상 안내)를 반환한다 → 프론트의 안전 안내와 일치.

					요청 예시:
					```json
					{ "question": "배송업체가 회신을 안 하면 언제까지 기다려요?" }
					```
					응답 예시 — 근거를 찾은 경우:
					```json
					{
					  "messageId": "b2c3d4e5-...",
					  "answer": "오늘 오후 3시까지 답이 없으면 물류팀에 공유하세요.",
					  "grounded": true,
					  "citations": [
					    { "sourceId": "a1b2c3d4-...", "title": "문제상황_대응방법.pdf", "locator": "청크 3/12", "fileId": "a1b2c3d4-...", "updatedAt": "2026-08-21T09:00:00Z" }
					  ],
					  "fallbackContact": null,
					  "answeredAt": "2026-08-25T02:00:00Z"
					}
					```
					`citations[].sourceId` == `fileId` == 업로드 파일(SourceDocument) id로 항상 같은 값이다.
					원문 메타데이터는 `GET /sources/{sourceId}`, 원본 파일 다운로드는 `GET /files/{fileId}/download`로 잇는다.
					`locator`는 문서 내 대략 위치(청크 순번). `title`은 파일명.
					응답 예시 — 자료에서 답을 못 찾은 경우:
					```json
					{
					  "messageId": "c3d4e5f6-...",
					  "answer": null,
					  "grounded": false,
					  "citations": [],
					  "fallbackContact": "업로드된 문서에서 답을 찾지 못했습니다. 인계자에게 직접 문의해주세요.",
					  "answeredAt": "2026-08-25T02:00:00Z"
					}
					```
					""")
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
					업로드된 문서를 분석해 구조화된 초안과 확인 질문을 만드는 **비동기 작업**을 시작한다(→ 상태 `ANALYZING`). 인계자만 가능.
					즉시 `202 Accepted`로 작업 정보를 반환하고, 진행률은 `GET /analysis`로 폴링한다.
					- 분석할 업로드 파일이 없음: `400`(code=`AI_NO_DOCUMENTS`)
					- 이미 진행 중인 작업: `409`(code=`AI_ANALYSIS_ALREADY_RUNNING`)
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

					응답 `AnalysisJobResponse`: `jobId`·`status`·`progress`(0~100 정수)·`currentStep`(사람이 읽는 현재 단계 문구)·`error`(실패 시 사유, 아니면 null)·`updatedAt`.
					- 진행 중: `QUEUED`·`PARSING`·`INDEXING`·`GENERATING_QUESTIONS`·`GENERATING_DRAFT`
					- **완료 상태**: `COMPLETED` — 폴링 종료. 확인 질문이 있으면 `GET /questions`가 채워지고(본 상태 `ANSWERING`),
					  없으면 빈 배열(본 상태 `EDITING`)이니 바로 `GET /document`로 초안을 조회하면 된다.
					- **실패 상태**: `FAILED` — `error`에 사유. `POST /analysis/retry`로만 재시도 가능(재시도 가능한 유일한 실패).
					- 작업 없음: `404`(code=`AI_ANALYSIS_JOB_NOT_FOUND`)
					""")
	@GetMapping("/analysis")
	public AnalysisJobResponse getAnalysis(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));
		return analysisJobService.getLatest(handoverId);
	}

	@Operation(summary = "AI 분석 작업 재시도",
			description = """
					가장 최근 작업이 실패(`FAILED`)한 경우에만 새 분석 작업을 만든다. 인계자만 가능.
					- 실패 상태가 아닐 때 재시도: `409`(code=`AI_ANALYSIS_RETRY_NOT_ALLOWED`)
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
					- 아직 생성된 초안 없음: `404`(code=`AI_DRAFT_NOT_FOUND`)
					""")
	@GetMapping("/document")
	public HandoverDraftResponse getDraft(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireViewer(handover, currentUserId(authentication));

		return ragAnalysisService.getDraft(handoverId);
	}

	@Operation(summary = "인수인계 문서(초안) 수정",
			description = """
					사람이 직접 고친 문서 내용을 저장한다(자동저장). 인계자만 가능. 현재는 `content` 전체를 통째로 교체한다.
					(주의: 아직 낙관적 버전 잠금이 없어 여러 창에서 동시에 저장하면 마지막 저장이 이긴다.)
					- 인계자가 아니면: `403`(code=`HANDOVER_FORBIDDEN`)

					요청 예시:
					```json
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
					  }
					}
					```
					""")
	@PatchMapping("/document")
	public HandoverDraftResponse updateDraft(
			@PathVariable UUID handoverId,
			@Valid @RequestBody UpdateDraftRequest request,
			Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.updateDraft(handoverId, request.content());
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
			description = "서버에 저장된 문서를 `.md` 파일로 내려준다(`text/markdown`, `Content-Disposition: attachment`). 참여자 모두 가능.")
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
					AI가 초안 보완을 위해 만든 확인 질문(질문·설명·선택지·근거)을 반환한다. 인계자만 가능.
					`type=INTERVIEW`(추가 정보 인터뷰) 또는 `type=CONFLICT`(문서 간 충돌 해소)로 필터할 수 있다.
					각 항목의 `status`: `PENDING`(미응답)·`ANSWERED`(답변)·`SKIPPED`(건너뜀).
					**질문이 0개면 빈 배열**을 반환한다 — 이땐 답변 단계를 건너뛰고 바로 `GET /document`로 초안을 조회하면 된다.
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

	@Operation(summary = "AI 확인 질문에 답변",
			description = """
					선택지 선택 또는 직접 입력으로 답변을 저장/수정하거나 건너뛴다. 인계자만 가능. 답변하면 `status=ANSWERED`, 건너뛰면 `SKIPPED`.

					요청 규칙(`validCombination` 검증):
					- **답변**: `skipped=false` + `answer`에 내용(공백만은 불가).
					- **건너뛰기**: `skipped=true`만 보내면 된다. `answer`는 보내지 않거나 `null`. **빈 문자열("")도 보내지 말 것** — 건너뛸 때 `answer`가 있으면 검증 실패.
					- 규칙 위반(둘 다 없음/둘 다 있음): `400`(code=`AI_QUESTION_ANSWER_INVALID` 또는 검증 `VALIDATION_FAILED`).
					- 없는 질문: `404`(code=`AI_QUESTION_NOT_FOUND`)

					요청 예시 — 답변:
					```json
					{ "answer": "마케팅 확인 후 팀장 승인", "skipped": false }
					```
					요청 예시 — 건너뛰기:
					```json
					{ "answer": null, "skipped": true }
					```
					""")
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
					답변(및 건너뛴 항목)을 반영해 초안을 다시 생성하고 최신 문서를 반환한다(본 상태 → `EDITING`). 인계자만 가능.

					**모든 질문에 답할 필요는 없다** — 답하지 않을 질문은 건너뛰기(`SKIPPED`)만 해두면 된다. 즉 `PENDING`이 하나도 없으면 호출 가능.
					답변이 하나도 없고 전부 건너뛰었거나 **질문이 0개면** 초안 재생성 없이 그대로 완료 처리한다.
					- 아직 `PENDING`(답변·건너뛰기 안 한) 질문이 남아 있음: `409`(code=`AI_QUESTIONS_INCOMPLETE`)
					""")
	@PostMapping("/questions/complete")
	public HandoverDraftResponse completeQuestions(@PathVariable UUID handoverId, Authentication authentication) {
		Handover handover = loadHandover(handoverId);
		handoverPermission.requireOwner(handover, currentUserId(authentication));

		return ragAnalysisService.completeQuestions(handoverId);
	}

	@Operation(summary = "AI 원문 근거 목록",
			description = "AI 분석·답변에 참조되는 원문(문서 제목·위치·수정일·접근 경로) 목록을 반환한다. 단건 상세는 `GET /sources/{sourceId}`. 참여자 모두 가능.")
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
