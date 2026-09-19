package com.baton.ai;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import com.baton.ai.dto.EvidenceHighlight;

/** Extract text and positions from the very same output stream so offsets cannot drift. */
final class PdfTextExtractor extends PDFTextStripper {

	private final StringWriter text = new StringWriter();
	private final List<PdfTextLocation> locations = new ArrayList<>();

	private PdfTextExtractor() {
		setSortByPosition(true);
		setLineSeparator("\n");
		setPageEnd("\n\n");
	}

	static Extraction extract(byte[] bytes) throws IOException {
		try (PDDocument pdf = Loader.loadPDF(bytes)) {
			PdfTextExtractor reader = new PdfTextExtractor();
			reader.writeText(pdf, reader.text);
			return new Extraction(reader.text.toString(), List.copyOf(reader.locations));
		}
	}

	@Override
	protected void writeString(String value, List<TextPosition> positions) throws IOException {
		int start = text.getBuffer().length();
		super.writeString(value, positions);
		if (!value.isBlank()) {
			locations.add(new PdfTextLocation(start, text.getBuffer().length(), getCurrentPageNo(), box(positions)));
		}
	}

	private EvidenceHighlight box(List<TextPosition> positions) {
		// Rotated/vertical text needs a different transform: retain the page, do not invent coordinates.
		if (getCurrentPage().getRotation() != 0 || positions.isEmpty()
				|| positions.stream().anyMatch(p -> p.getDir() != 0)) {
			return null;
		}
		double width = getCurrentPage().getCropBox().getWidth();
		double height = getCurrentPage().getCropBox().getHeight();
		double left = positions.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0);
		double top = positions.stream().mapToDouble(p -> p.getYDirAdj() - p.getHeightDir()).min().orElse(0);
		double right = positions.stream().mapToDouble(p -> p.getXDirAdj() + p.getWidthDirAdj()).max().orElse(0);
		double bottom = positions.stream().mapToDouble(TextPosition::getYDirAdj).max().orElse(0);
		left = Math.max(0, left); top = Math.max(0, top);
		right = Math.min(width, right); bottom = Math.min(height, bottom);
		if (width <= 0 || height <= 0 || right <= left || bottom <= top) return null;
		return new EvidenceHighlight(getCurrentPageNo(), left / width, top / height,
				(right - left) / width, (bottom - top) / height);
	}

	record Extraction(String text, List<PdfTextLocation> locations) { }
}
