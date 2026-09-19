-- RAG 답변에서 서로 다른 최신 근거의 충돌 여부를 프론트에 전달한다.
-- 운영(ddl-auto=validate) 배포 전에 실행한다. 여러 번 실행해도 안전하다.
ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS requires_confirmation boolean NOT NULL DEFAULT false;

-- 하이브리드 검색이 한 인수인계의 벡터 청크만 빠르게 읽도록 한다.
DO $$
BEGIN
    IF to_regclass('public.vector_store') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_vector_store_handover_id
            ON vector_store ((metadata->>'handoverId'));
    END IF;
END $$;
