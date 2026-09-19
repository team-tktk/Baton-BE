-- Additive; existing sources continue to return quote-only evidence until re-uploaded.
ALTER TABLE source_documents ADD COLUMN IF NOT EXISTS pdf_text_locations jsonb;
