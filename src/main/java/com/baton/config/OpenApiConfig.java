package com.baton.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI batonOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("Baton API")
						.version("v0.0.1")
						.description(API_DESCRIPTION));
	}

	private static final String API_DESCRIPTION = """
			Baton 백엔드 API. 인수인계 문서를 AI로 초안 생성하고, 인수자 질의응답과 관리자 검토까지 다룬다.

			## 공통 규칙

			- **Base path**: 모든 경로는 /api/v1 하위. 시간은 ISO-8601(UTC), ID는 UUID.
			- **인증**: HTTP-only 세션 쿠키 기반. POST /auth/login 성공 시 세션 쿠키가 발급되고,
			  이후 요청은 브라우저가 쿠키를 자동 전송(fetch(..., { credentials: 'include' }))하면 인증된다.
			  미인증 요청은 401(code=AUTH_REQUIRED).
			- **권한**: URL의 역할 값이 아니라 로그인 사용자와 HandoverParticipant 관계로 검사한다.
			  인계자(owner)는 자신이 만든 문서만 수정, 인수자(recipient)는 자신에게 전달된 문서만 조회,
			  관리자(reviewer)는 자신이 지정된 건만 검토/승인한다. 권한 없으면 403(code=HANDOVER_FORBIDDEN).
			- **에러 형식**: RFC 7807 ProblemDetail을 따른다. 모든 에러 응답은 아래 5개 필드를 공통으로 가진다:
			  status(HTTP 상태), code(기계 판별용, ErrorCode 이름), title(기본 메시지), detail(상황별 상세), instance(요청 경로).
			  검증 실패(VALIDATION_FAILED)일 땐 fieldErrors(어떤 필드가 왜 틀렸는지)를 추가로 담는다.
			  프론트는 HTTP status가 아니라 code로 분기하는 것을 권장. code 카탈로그는 아래 "에러 code" 참고.
			- **페이지네이션**: 목록은 cursor(직전 페이지 마지막 항목 id)+size(기본 20, 최대 100) 키셋 방식.
			  응답의 nextCursor/hasNext로 다음 페이지를 잇는다.
			- **멱등성**: 제출/수신확인/완료/승인/보완요청은 같은 요청을 반복해도 상태가 중복 변경되지 않는다.
			- **사용자 요약**: 목록·상세에 등장하는 인계자(owner)·참여자(participants)는 userId뿐 아니라
			  이름·팀·직책(UserSummaryResponse: id/name/team/position)까지 함께 내려간다 →
			  프론트는 별도 사용자 조회 없이 화면에 바로 표시한다. (소속 조직은 현재 별도 컬럼이 없어 team 문자열에 함께 담긴다.)

			## 인수인계 상태 흐름

			DRAFT → ANALYZING → ANSWERING → EDITING → PENDING_REVIEW → (REVISION_REQUESTED ↔ EDITING) → APPROVED → COMPLETED

			인수자의 수신 여부(UNREAD/READ)는 본 상태와 별개로 참여자 단위로 관리된다.

			## Enum 카탈로그

			- **HandoverStatus**(본 상태): DRAFT·ANALYZING·ANSWERING·EDITING·PENDING_REVIEW·REVISION_REQUESTED·APPROVED·COMPLETED
			- **ReceiptStatus**(인수자 수신): UNREAD·READ
			- **ReceivedFilter**(받은 목록 탭): UNREAD·IN_PROGRESS·COMPLETED
			- **ParticipantRole**: RECIPIENT(인수자)·REVIEWER(관리자). 인계자는 참여자가 아니라 owner.
			- **AnalysisJobStatus**(AI 분석 작업): QUEUED·PARSING·INDEXING·GENERATING_QUESTIONS·GENERATING_DRAFT = 진행 중,
			  COMPLETED = 완료(종료), FAILED = 실패(종료). progress는 0~100 정수.
			- **SourceDocumentStatus**(첨부 파일 처리): EXTRACTING(추출·임베딩 진행 중)·MASKING_REVIEW(마스킹 검수 대기)·INDEXING(검수 확정 후 임베딩 중)·INDEXED(완료)·FAILED(실패 → 재처리 가능)
			- **MaskingType**(마스킹 유형): EMAIL·PHONE·ACCOUNT·RRN(주민등록번호)·CARD·BUSINESS_NO(사업자등록번호)·CUSTOM(직접 지정)
			- **MaskingOrigin**(마스킹 항목 출처): DETECTED(자동 탐지)·MANUAL(직접 추가)
			- **ClarificationQuestionStatus**(확인 질문): PENDING(미응답)·ANSWERED(답변)·UNKNOWN(모름)·NOT_APPLICABLE(해당 없음)·DEFERRED(나중에 답하기)
			- **ClarificationQuestionType**: INTERVIEW(추가 정보 인터뷰)·CONFLICT(문서 간 충돌 해소)
			- **ReadinessArea**(준비도 평가 영역): SCOPE(업무 범위)·PROCEDURE(실행 절차)·COMPLETION(완료 기준)·EXCEPTION(예외 대응)·
			  SCHEDULE(일정)·CONTACTS(담당자)·ACCESS(접근 권한)·EVIDENCE(근거와 최신성)
			- **ReadinessStatus**(영역 평가): SUFFICIENT(충분)·PARTIAL(일부 부족)·MISSING(누락)·CONFLICT(충돌)
			- **ReadinessGrade**(전체 등급): READY(준비 완료)·NEEDS_IMPROVEMENT(보완 필요)·NOT_READY(준비 부족)
			- **ReadinessFixStatus**(보완안): NEEDS_INPUT(수정안 없음 — 질문 답변 후 보완안 만들기 대기)·PROPOSED(수정안이 있는 영역이 하나 이상, 확인 대기)·APPLIED(적용됨)·DISCARDED(취소됨)
			- **DraftSection**(문서 영역): PURPOSE·COMPLETION_CRITERIA·ONGOING_TASKS·RECURRING_TASKS·RULES_AND_EXCEPTIONS·
			  STAKEHOLDERS·TOOLS·SCHEDULE·ACCESS_ACCOUNTS·FIRST_WEEK_CHECKLIST·CONFIRMED_CRITERIA

			## 파일 업로드 정책

			- 허용 확장자: pdf, docx, xlsx, pptx(대소문자 무시). 확장자로 판별한다.
			  MIME은 브라우저가 보낸 Content-Type을 그대로 저장하며(예: application/pdf,
			  application/vnd.openxmlformats-officedocument.wordprocessingml.document 등) 검증 기준은 아니다.
			- 최대 파일 크기: **50MB**(요청 전체도 50MB). 초과 시 413.
			- 인수인계당 파일 개수: 별도 상한 없음(현재 무제한).
			- 업로드 응답 FileUploadResponse.sourceDocumentId == 파일 목록 FileMetadataResponse.id == 근거 Citation.sourceId/fileId
			  == 다운로드 경로의 {fileId}. **모두 같은 SourceDocument id다.**
			- 삭제 가능 상태: 처리 중(EXTRACTING·INDEXING)이 아니면 삭제 가능. 처리 중 삭제는 409(code=AI_SOURCE_DOCUMENT_PROCESSING).
			- 다운로드: Content-Type은 저장된 MIME(없으면 application/octet-stream), Content-Disposition: attachment; filename&#42;=UTF-8''... 제공.

			## 마스킹 검수 흐름(서버 설정 app.masking.enabled가 켜진 경우)

			1. 업로드 → 파일 status=MASKING_REVIEW (이 시점까지 원문은 외부로 전송되지 않음)
			2. GET /files의 remainingReviewCount와 GET /files/{fileId}/masking으로 검수 화면 구성
			3. PATCH·POST·DELETE /files/{fileId}/masking/candidates로 적용/해제·직접 추가/삭제
			4. POST /files/{fileId}/masking/confirm → 마스킹된 텍스트로 교체·원문 삭제·임베딩 → status=INDEXED
			5. 모든 파일이 확정된 뒤 POST /analysis (아니면 409 MASKING_NOT_CONFIRMED)

			## AI 분석 폴링

			POST /analysis는 202로 즉시 반환하고, GET /analysis로 상태를 폴링한다. **권장 주기 2~3초.**
			status가 COMPLETED면 완료, FAILED면 실패(error에 사유). FAILED만 POST /analysis/retry로 재시도 가능하며,
			그 외 상태에서 재시도하면 409(code=AI_ANALYSIS_RETRY_NOT_ALLOWED). 완료 후 확인 질문이 없으면
			(GET /questions가 빈 배열) 바로 GET /document로 초안을 조회하면 된다.

			## 에러 code

			AUTH_REQUIRED·AUTH_INVALID_CREDENTIALS·AUTH_EMAIL_DUPLICATE·VALIDATION_FAILED·BAD_REQUEST·NOT_FOUND·CONFLICT·INTERNAL_ERROR·
			HANDOVER_NOT_FOUND·HANDOVER_FORBIDDEN·HANDOVER_NOT_EDITABLE·HANDOVER_INVALID_PARTICIPANT·HANDOVER_INVALID_STATE·
			REVIEW_CHECKLIST_INCOMPLETE·AI_UNSUPPORTED_FILE_TYPE·AI_FILE_PARSE_FAILED·AI_SOURCE_DOCUMENT_NOT_FOUND·AI_NO_DOCUMENTS·
			AI_DRAFT_NOT_FOUND·AI_QUESTION_NOT_FOUND·AI_QUESTION_ANSWER_INVALID·AI_SOURCE_DOCUMENT_PROCESSING·AI_QUESTIONS_INCOMPLETE·
			AI_ANALYSIS_JOB_NOT_FOUND·AI_ANALYSIS_ALREADY_RUNNING·AI_ANALYSIS_RETRY_NOT_ALLOWED·AI_DRAFT_REVISION_CONFLICT·
			READINESS_NOT_EVALUATED·READINESS_STALE·READINESS_ITEM_SUFFICIENT·READINESS_FIX_NOT_FOUND·READINESS_FIX_INVALID_STATE.
			각 엔드포인트 설명에 발생 가능한 code를 함께 적어둔다.

			## 운영 환경(배포)

			- 운영 API는 **HTTPS**로 서빙해야 한다(HTTPS 프론트에서 HTTP API 직접 호출 시 mixed-content 차단).
			  HTTPS 종단 또는 동일 도메인 리버스 프록시로 노출한다.
			- CORS 허용 origin은 app.cors.allowed-origins(운영은 환경변수 APP_CORS_ALLOWEDORIGINS)로 관리하며
			  패턴 매칭을 지원한다(예: https://baton-fe.vercel.app, https://baton-fe-&#42;.vercel.app).
			  Access-Control-Allow-Credentials: true로 응답하므로 프론트는 credentials: 'include' 필수.
			- 세션 쿠키(BATON_SESSION): HttpOnly=true, SameSite=Lax. 운영 프로파일에서 Secure=true(HTTPS 전용).
			  프론트와 API 도메인이 다르면(교차 사이트) 브라우저가 쿠키를 붙이려면 API가 HTTPS + Secure이고
			  SameSite=None이어야 한다. 그래서 **같은 상위 도메인(서브도메인 구성) 또는 리버스 프록시로 동일 출처를 권장**한다.
			""";
}
