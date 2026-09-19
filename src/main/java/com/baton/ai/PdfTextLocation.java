package com.baton.ai;

import com.baton.ai.dto.EvidenceHighlight;

/** UTF-16 offsets in extractedText; stores geometry only, never another copy of raw text. */
public record PdfTextLocation(int start, int end, int page, EvidenceHighlight highlight) {
}
