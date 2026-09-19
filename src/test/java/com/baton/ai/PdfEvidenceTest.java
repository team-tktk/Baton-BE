package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class PdfEvidenceTest {
	@Test
	void indexingKeepsPdfPagesSeparateEvenWhenBothFitOneTokenChunk() throws Exception {
		var extracted = PdfTextExtractor.extract(pdf(0));
		var chunks = EvidenceChunker.split(extracted.text(), extracted.locations(),
				org.springframework.ai.transformer.splitter.TokenTextSplitter.builder().build());
		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(0).getMetadata()).containsEntry("page", 1);
		assertThat(chunks.get(1).getMetadata()).containsEntry("page", 2);
		assertThat(chunks.get(1).getText()).contains("Check coupon").doesNotContain("Introduction");
		SourceDocument source = SourceDocument.create(UUID.randomUUID(), "manual.pdf", "application/pdf", 10, "key");
		source.setPdfTextLocations(extracted.locations());
		source.markIndexed(extracted.text(), List.of());
		var citation = EvidenceCitations.fromMatch(source, chunks.get(1));
		assertThat(citation.page()).isEqualTo(2);
		assertThat(citation.highlights()).allSatisfy(h -> assertThat(h.page()).isEqualTo(2));
	}

	@Test
	void oldPersistedCitationsDeserializeWithEmptyHighlights() throws Exception {
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		var citation = mapper.readValue("{\"title\":\"old.pdf\",\"locator\":\"chunk 1\",\"type\":\"FILE\"}",
				com.baton.ai.dto.Citation.class);
		assertThat(citation.page()).isNull();
		assertThat(citation.highlights()).isEmpty();
	}

	@Test
	void extractsSecondPageAndRealTextCoordinates() throws Exception {
		var extracted = PdfTextExtractor.extract(pdf(0));
		var evidence = EvidenceLocator.locate(extracted.text(), "Check coupon dates first.", extracted.locations());
		assertThat(evidence.page()).isEqualTo(2);
		assertThat(evidence.quote()).isEqualTo("Check coupon dates first.");
		assertThat(evidence.highlights()).hasSize(1);
		var box = evidence.highlights().getFirst();
		assertThat(box.page()).isEqualTo(2);
		assertThat(box.x()).isCloseTo(72.0 / 612, org.assertj.core.data.Offset.offset(0.001));
		assertThat(box.y()).isBetween(0.10, 0.13);
		assertThat(box.width()).isPositive();
		assertThat(box.height()).isPositive();
	}

	@Test
	void rotatedPageKeepsPageWithoutMisleadingBox() throws Exception {
		var extracted = PdfTextExtractor.extract(pdf(90));
		var evidence = EvidenceLocator.locate(extracted.text(), "Check coupon dates first.", extracted.locations());
		assertThat(evidence.page()).isEqualTo(2);
		assertThat(evidence.highlights()).isEmpty();
	}

	@Test
	void whitespaceDifferencesMatchButAmbiguousAndInventedQuotesDoNot() {
		assertThat(EvidenceLocator.locate("Check\n coupon  dates", "Check coupon dates", List.of()).quote())
				.isEqualTo("Check\n coupon  dates");
		assertThat(EvidenceLocator.locate("same same", "same", List.of()).page()).isNull();
		assertThat(EvidenceLocator.locate("safe text", "invented text", List.of()).quote()).isNull();
	}

	@Test
	void legacyAndNonPdfSourcesReturnVerifiedQuoteWithoutCoordinates() {
		SourceDocument source = SourceDocument.create(UUID.randomUUID(), "manual.docx", "application/zip", 10, "key");
		source.markIndexed("The approval owner is Operations.", List.of());
		var evidence = EvidenceCitations.resolve(source, "approval owner is Operations.");
		assertThat(evidence.quote()).isEqualTo("approval owner is Operations.");
		assertThat(evidence.page()).isNull();
		assertThat(evidence.highlights()).isEmpty();
	}

	@Test
	void pendingMaskingAndDisabledSourcesNeverReturnRawQuotes() {
		SourceDocument source = SourceDocument.create(UUID.randomUUID(), "manual.pdf", "application/pdf", 10, "key");
		source.markMaskingReview("private@example.com");
		assertThat(EvidenceCitations.resolve(source, "private@example.com").quote()).isNull();
		source.markIndexed("safe text", List.of());
		source.setEnabled(false);
		assertThat(EvidenceCitations.resolve(source, "safe text").quote()).isNull();
	}

	@Test
	void staleChunkOffsetsCannotRevealUnrelatedText() {
		SourceDocument source = SourceDocument.create(UUID.randomUUID(), "manual.pdf", "application/pdf", 10, "key");
		source.markIndexed("safe text", List.of());
		Document match = new Document("private@example.com");
		match.getMetadata().put("evidenceStart", 0);
		match.getMetadata().put("evidenceEnd", 9);
		assertThat(EvidenceCitations.fromMatch(source, match).quote()).isNull();
	}

	static byte[] pdf(int rotation) throws Exception {
		try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (String text : List.of("Introduction.", "Check coupon dates first.")) {
				PDPage page = new PDPage(PDRectangle.LETTER);
				page.setRotation(rotation);
				pdf.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					content.newLineAtOffset(72, 700);
					content.showText(text);
					content.endText();
				}
			}
			pdf.save(out);
			return out.toByteArray();
		}
	}
}
