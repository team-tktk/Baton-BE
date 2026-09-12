package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.baton.common.BusinessException;

class FileSignatureValidatorTest {

	private final FileSignatureValidator validator = new FileSignatureValidator();

	@Test
	void acceptsRealPdf() {
		byte[] content = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);

		assertThatCode(() -> validator.validate("pdf", content)).doesNotThrowAnyException();
	}

	@Test
	void rejectsFileClaimingPdfButIsNotPdf() {
		byte[] content = "hello world, not a pdf".getBytes(StandardCharsets.US_ASCII);

		assertThatThrownBy(() -> validator.validate("pdf", content))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("PDF");
	}

	@Test
	void acceptsRealDocx() throws IOException {
		byte[] content = zipWithEntry("word/document.xml");

		assertThatCode(() -> validator.validate("docx", content)).doesNotThrowAnyException();
	}

	@Test
	void rejectsPlainZipClaimingToBeDocx() throws IOException {
		byte[] content = zipWithEntry("readme.txt");

		assertThatThrownBy(() -> validator.validate("docx", content))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("DOCX");
	}

	@Test
	void rejectsExecutableEvenWithPdfExtension() {
		byte[] windowsExeSignature = {0x4D, 0x5A, 0x00, 0x00};

		assertThatThrownBy(() -> validator.validate("pdf", windowsExeSignature))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("실행 파일");
	}

	@Test
	void rejectsShellScriptEvenWithDocxExtension() {
		byte[] shebang = "#!/bin/bash\necho hacked".getBytes(StandardCharsets.US_ASCII);

		assertThatThrownBy(() -> validator.validate("docx", shebang))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("실행 파일");
	}

	private byte[] zipWithEntry(String entryName) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
			zos.putNextEntry(new ZipEntry(entryName));
			zos.write("<xml/>".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		return buffer.toByteArray();
	}
}
