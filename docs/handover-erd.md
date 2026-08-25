# Handover 도메인 ERD (초안)

> 기준: 분석 문서 + 기존 코드 컨벤션(`User`, `SourceDocument` 이미 존재).
> JPA `ddl-auto`로 엔티티 = 테이블이 되므로, 이 ERD는 그대로 엔티티 설계도이기도 하다.

## 설계 결정 (팀 합의 포인트)

1. **participant role = enum 컬럼** (별도 테이블 X). 한 handover에 인계자·인수자·관리자를 `HandoverParticipant` 한 테이블 + `role` enum으로 표현. 권한 검사는 (userId, handoverId, role)로 조회.
2. **WorkScope / HandoverTask = 별도 엔티티**. 문서가 `workScopes[]`, `tasks.{id}.nextAction` 같은 개별 편집을 요구하므로 문자열배열이 아니라 엔티티로.
3. **`SourceDocument` → `Handover` FK로 승격**. 지금은 `handoverId` raw UUID만 들고 있음. 진짜 `@ManyToOne`으로 묶어 정합성 확보 (AI 담당과 협의).

## 소유 경계

- 🟦 **handover 담당(나)**: Handover, HandoverParticipant, WorkScope, HandoverTask, HandoverDocument, DocumentVersion, ReviewChecklist, ReviewComment
- 🟨 **AI 담당**: SourceDocument(존재), ClarificationQuestion/Answer, ChatMessage/Citation, AnalysisJob
- 🟩 **auth 담당(존재)**: User

```mermaid
erDiagram
    User ||--o{ Handover : "owns (인계자)"
    User ||--o{ HandoverParticipant : "참여"
    Handover ||--o{ HandoverParticipant : "참여자"
    Handover ||--o{ WorkScope : "업무범위"
    Handover ||--o{ HandoverTask : "개별업무"
    Handover ||--|| HandoverDocument : "문서(1:1)"
    HandoverDocument ||--o{ DocumentVersion : "버전이력"
    Handover ||--o{ SourceDocument : "첨부/원본"
    Handover ||--o| ReviewChecklist : "검토체크"
    Handover ||--o{ ReviewComment : "코멘트"
    Handover ||--o{ ClarificationQuestion : "보완질문(AI)"
    ClarificationQuestion ||--o| ClarificationAnswer : "답변"
    Handover ||--o{ ChatMessage : "AI대화(AI)"
    ChatMessage ||--o{ Citation : "근거"

    User {
        uuid id PK
        string email UK
        string password_hash
        string name
        string team
        instant created_at
    }

    Handover {
        uuid id PK
        uuid owner_id FK "인계자 User"
        string title
        enum status "DRAFT|ANALYZING|ANSWERING|EDITING|PENDING_REVIEW|REVISION_REQUESTED|APPROVED|COMPLETED"
        instant submitted_at "nullable"
        instant created_at
        instant updated_at
    }

    HandoverParticipant {
        uuid id PK
        uuid handover_id FK
        uuid user_id FK
        enum role "OWNER|RECIPIENT|REVIEWER"
        enum receipt_status "UNREAD|READ, 인수자만"
        instant created_at
    }

    WorkScope {
        uuid id PK
        uuid handover_id FK
        string title
        text description
        int sort_order
    }

    HandoverTask {
        uuid id PK
        uuid handover_id FK
        string title
        text next_action
        enum status "TODO|IN_PROGRESS|DONE"
        instant completed_at "nullable"
    }

    HandoverDocument {
        uuid id PK
        uuid handover_id FK "1:1"
        int current_version "낙관락"
        json content "구조화 섹션"
        instant updated_at
    }

    DocumentVersion {
        uuid id PK
        uuid document_id FK
        int version
        uuid editor_id FK "User"
        text change_summary
        json snapshot
        instant created_at
    }

    SourceDocument {
        uuid id PK
        uuid handover_id FK "raw UUID→FK 승격"
        string file_name
        string mime_type
        enum status "EXTRACTING|INDEXED|FAILED"
        instant created_at
        instant updated_at
    }

    ReviewChecklist {
        uuid id PK
        uuid handover_id FK "1:1"
        json items "체크항목 상태"
        uuid reviewer_id FK
        instant updated_at
    }

    ReviewComment {
        uuid id PK
        uuid handover_id FK
        uuid author_id FK "User"
        text content
        instant created_at
        instant updated_at
    }

    ClarificationQuestion {
        uuid id PK
        uuid handover_id FK
        enum type "INTERVIEW|CONFLICT"
        text question
        json options
        json rationale
    }

    ClarificationAnswer {
        uuid id PK
        uuid question_id FK "1:1"
        text answer
        boolean skipped
        instant answered_at
    }

    ChatMessage {
        uuid id PK
        uuid handover_id FK
        enum sender "USER|AI"
        text content
        boolean grounded
        instant created_at
    }

    Citation {
        uuid id PK
        uuid message_id FK
        uuid source_document_id FK
        string title
        string locator "예: 3쪽 > 배송지연"
        instant updated_at
    }
```
