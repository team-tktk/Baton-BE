-- 웹 링크·Slack 자료를 기존 source_documents/RAG 흐름에 통합한다.
-- 운영(ddl-auto=validate) 배포 전에 실행한다. 여러 번 실행해도 안전하다.
BEGIN;

ALTER TABLE source_documents ADD COLUMN IF NOT EXISTS source_type varchar(20);
UPDATE source_documents SET source_type = 'FILE' WHERE source_type IS NULL;
ALTER TABLE source_documents ALTER COLUMN source_type SET NOT NULL;

ALTER TABLE source_documents ADD COLUMN IF NOT EXISTS description varchar(2000);
ALTER TABLE source_documents ADD COLUMN IF NOT EXISTS original_url varchar(2048);
ALTER TABLE source_documents ADD COLUMN IF NOT EXISTS conversation_name varchar(255);
ALTER TABLE source_documents ADD COLUMN IF NOT EXISTS source_occurred_at timestamp(6) with time zone;
ALTER TABLE source_documents ADD COLUMN IF NOT EXISTS enabled boolean DEFAULT true;
UPDATE source_documents SET enabled = true WHERE enabled IS NULL;
ALTER TABLE source_documents ALTER COLUMN enabled SET NOT NULL;

ALTER TABLE source_documents DROP CONSTRAINT IF EXISTS source_documents_source_type_check;
ALTER TABLE source_documents ADD CONSTRAINT source_documents_source_type_check
    CHECK (source_type IN ('FILE', 'WEB_LINK', 'SLACK_MESSAGE'));

CREATE INDEX IF NOT EXISTS idx_source_documents_handover_type
    ON source_documents (handover_id, source_type);

COMMIT;
