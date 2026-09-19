package com.baton.ai.dto;

/** Top-left origin, normalized to the unrotated PDF crop box. Page is one-based. */
public record EvidenceHighlight(int page, double x, double y, double width, double height) {
}
