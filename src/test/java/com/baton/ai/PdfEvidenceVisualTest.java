package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/** Reproducible visual fixture: use the production extraction coordinates on the rendered PDF. */
class PdfEvidenceVisualTest {
	@Test
	void highlightOverlapsRenderedTextAndWritesManualQaArtifacts() throws Exception {
		byte[] bytes = PdfEvidenceTest.pdf(0);
		var extracted = PdfTextExtractor.extract(bytes);
		var evidence = EvidenceLocator.locate(extracted.text(), "Check coupon dates first.", extracted.locations());
		assertThat(evidence.page()).isEqualTo(2);
		assertThat(evidence.highlights()).hasSize(1);
		Path output = Path.of("build", "pdf-evidence-verification");
		Files.createDirectories(output);
		Files.write(output.resolve("sample.pdf"), bytes);
		Files.writeString(output.resolve("evidence.json"), new com.fasterxml.jackson.databind.ObjectMapper()
				.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
		try (var pdf = Loader.loadPDF(bytes)) {
			BufferedImage rendered = new PDFRenderer(pdf).renderImageWithDPI(1, 120);
			var box = evidence.highlights().getFirst();
			int x = (int) (box.x() * rendered.getWidth());
			int y = (int) (box.y() * rendered.getHeight());
			int width = (int) Math.ceil(box.width() * rendered.getWidth());
			int height = (int) Math.ceil(box.height() * rendered.getHeight());
			int inkPixels = 0;
			for (int py = y; py < y + height; py++) {
				for (int px = x; px < x + width; px++) {
					if ((rendered.getRGB(px, py) & 0xffffff) < 0x808080) inkPixels++;
				}
			}
			assertThat(inkPixels).as("server box overlaps real rendered glyphs").isGreaterThan(100);
			Graphics2D graphics = rendered.createGraphics();
			graphics.setColor(new Color(255, 210, 0, 90));
			graphics.fillRect(x, y, width, height);
			graphics.dispose();
			ImageIO.write(rendered, "png", output.resolve("page-2-highlight.png").toFile());
		}
	}
}
