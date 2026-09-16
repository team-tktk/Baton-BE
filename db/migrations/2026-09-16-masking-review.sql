-- 민감정보 마스킹 검수(#45): 파일 상태 2개 추가, 검수 확정 시각, 마스킹 후보 테이블.
-- 운영은 ddl-auto: validate라 스키마를 자동으로 바꾸지 않는다. 새 버전 배포 "전에" 운영 DB에 한 번 실행한다.
-- 로컬(ddl-auto: update)은 테이블·컬럼은 자동으로 만들어지지만, 기존 CHECK 제약은 바꾸지 않으므로
-- 새 상태값(MASKING_REVIEW, INDEXING)을 쓰려면 로컬에서도 한 번 실행해야 한다(여러 번 실행해도 무해).
-- 기존 행은 건드리지 않는다. 이미 INDEXED인 파일은 검수 없이 처리된 것으로 본다(masking_confirmed_at = NULL).

BEGIN;

-- 파일 상태: MASKING_REVIEW(검수 대기), INDEXING(검수 확정 후 임베딩 중) 추가
ALTER TABLE source_documents DROP CONSTRAINT IF EXISTS source_documents_status_check;
ALTER TABLE source_documents ADD CONSTRAINT source_documents_status_check
    CHECK (status IN ('EXTRACTING', 'MASKING_REVIEW', 'INDEXING', 'INDEXED', 'FAILED'));

-- 사용자가 마스킹 검수를 확정한 시각
ALTER TABLE source_documents ADD COLUMN IF NOT EXISTS masking_confirmed_at timestamp(6) with time zone;

-- 파일별 마스킹 후보. offset은 source_documents.extracted_text 기준 [start, end) 구간. 원문 값은 저장하지 않는다.
CREATE TABLE IF NOT EXISTS masking_candidates (
    id                 uuid PRIMARY KEY,
    source_document_id uuid NOT NULL,
    handover_id        uuid NOT NULL,
    type               varchar(20) NOT NULL
        CHECK (type IN ('EMAIL', 'PHONE', 'ACCOUNT', 'RRN', 'CARD', 'BUSINESS_NO', 'CUSTOM')),
    origin             varchar(20) NOT NULL
        CHECK (origin IN ('DETECTED', 'MANUAL')),
    start_offset       integer NOT NULL,
    end_offset         integer NOT NULL,
    confidence         float(53) NOT NULL,
    applied            boolean NOT NULL,
    needs_review       boolean NOT NULL,
    reviewed           boolean NOT NULL,
    preview            varchar(120) NOT NULL,
    created_at         timestamp(6) with time zone NOT NULL,
    updated_at         timestamp(6) with time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_masking_candidates_source_document
    ON masking_candidates (source_document_id, start_offset);

COMMIT;
