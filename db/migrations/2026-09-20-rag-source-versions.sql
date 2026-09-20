-- 검색에서는 현재 버전만 사용하되, 수정·삭제 전 자료는 감사/복구용으로 보관한다.
-- 운영 배포 전에 실행한다. 여러 번 실행해도 안전하다.
CREATE TABLE IF NOT EXISTS rag_source_versions (
    id                uuid PRIMARY KEY,
    handover_id       uuid NOT NULL,
    source_id         uuid NOT NULL,
    source_type       varchar(30) NOT NULL,
    title             varchar(255) NOT NULL,
    locator           varchar(500),
    content           text NOT NULL,
    content_hash      varchar(64) NOT NULL,
    source_revision   bigint,
    source_updated_at timestamptz,
    archived_at       timestamptz NOT NULL,
    CONSTRAINT uk_rag_source_version_content UNIQUE (source_id, content_hash)
);

CREATE INDEX IF NOT EXISTS idx_rag_source_versions_handover
    ON rag_source_versions (handover_id, archived_at DESC);
CREATE INDEX IF NOT EXISTS idx_rag_source_versions_source
    ON rag_source_versions (source_id, archived_at DESC);

-- 인수인계 자체를 삭제하면 보관 중인 과거 내용도 반드시 함께 제거한다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_rag_source_versions_handover'
    ) THEN
        ALTER TABLE rag_source_versions
            ADD CONSTRAINT fk_rag_source_versions_handover
            FOREIGN KEY (handover_id) REFERENCES handovers(id) ON DELETE CASCADE;
    END IF;
END $$;
