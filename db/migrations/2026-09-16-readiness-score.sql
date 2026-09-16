-- 인수인계 준비도: 문서 버전(revision), 평가 결과, 부족 항목 보완안.
-- 운영은 ddl-auto: validate라 스키마를 자동으로 바꾸지 않는다. 새 버전 배포 "전에" 운영 DB에 한 번 실행한다.
-- 로컬(ddl-auto: update)은 자동으로 만들어지므로 실행하지 않아도 된다(실행해도 무해).

BEGIN;

-- 문서 버전. 내용이 바뀔 때마다 1씩 오르며, 사용자가 본 버전과 다르면 보완안 적용·저장을 막는다.
ALTER TABLE handover_drafts ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS readiness_evaluations (
    id             uuid PRIMARY KEY,
    handover_id    uuid NOT NULL,
    rubric_version varchar(20) NOT NULL,
    content_hash   varchar(64) NOT NULL,
    draft_revision bigint NOT NULL,
    score          integer NOT NULL,
    items          jsonb NOT NULL,
    created_at     timestamp(6) with time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_readiness_evaluations_handover
    ON readiness_evaluations (handover_id, created_at);

CREATE TABLE IF NOT EXISTS readiness_fixes (
    id               uuid PRIMARY KEY,
    handover_id      uuid NOT NULL,
    evaluation_id    uuid NOT NULL,
    area             varchar(30) NOT NULL
        CHECK (area IN ('SCOPE', 'PROCEDURE', 'COMPLETION', 'EXCEPTION', 'SCHEDULE', 'CONTACTS', 'ACCESS', 'EVIDENCE')),
    section          varchar(30) NOT NULL
        CHECK (section IN ('PURPOSE', 'COMPLETION_CRITERIA', 'ONGOING_TASKS', 'RECURRING_TASKS', 'RULES_AND_EXCEPTIONS',
                           'STAKEHOLDERS', 'TOOLS', 'SCHEDULE', 'ACCESS_ACCOUNTS', 'FIRST_WEEK_CHECKLIST',
                           'CONFIRMED_CRITERIA')),
    status           varchar(20) NOT NULL
        CHECK (status IN ('NEEDS_INPUT', 'PROPOSED', 'APPLIED', 'DISCARDED')),
    base_revision    bigint NOT NULL,
    applied_revision bigint,
    before_content   jsonb NOT NULL,
    after_content    jsonb,
    change_summary   text,
    questions        jsonb NOT NULL,
    evidence         jsonb NOT NULL,
    created_at       timestamp(6) with time zone NOT NULL,
    updated_at       timestamp(6) with time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_readiness_fixes_handover ON readiness_fixes (handover_id);

COMMIT;
