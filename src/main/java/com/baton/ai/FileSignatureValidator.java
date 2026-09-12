package com.baton.ai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

/**
 * 확장자만 보고 판단하지 않고, 실제 파일 내용(매직바이트)까지 확인해서 위장 업로드
 * (예: 확장자만 .pdf로 바꾼 실행파일)를 막는다.
 * DOCX/XLSX/PPTX는 전부 ZIP 컨테이너라 매직바이트만으로는 서로 구분이 안 돼서,
 * ZIP 안의 표식 파일(word/document.xml 등)까지 확인해야 진짜 그 형식인지 알 수 있다.
 */
@Component
class FileSignatureValidator {

	private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46}; // %PDF
	private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04}; // PK\3\4

	/** 대표적인 실행파일/스크립트 매직바이트. 확장자를 위장해도 이 서명으로 걸러낸다. */
	private static final Map<String, byte[]> EXECUTABLE_SIGNATURES = Map.of(
			"Windows 실행파일(PE/EXE)", new byte[]{0x4D, 0x5A},
			"Linux 실행파일(ELF)", new byte[]{0x7F, 0x45, 0x4C, 0x46},
			"macOS 실행파일(Mach-O)", new byte[]{(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE},
			"셸 스크립트", new byte[]{'#', '!'});

	/** OOXML 포맷별로 ZIP 안에 반드시 있어야 하는 표식 엔트리. */
	private static final Map<String, String> OOXML_MARKER_ENTRY = Map.of(
			"docx", "word/document.xml",
			"xlsx", "xl/workbook.xml",
			"pptx", "ppt/presentation.xml");

	void validate(String extension, byte[] content) {
		rejectIfExecutable(content);

		if ("pdf".equals(extension)) {
			if (!startsWith(content, PDF_SIGNATURE)) {
				throw unsupported("파일 내용이 PDF 형식이 아닙니다.");
			}
			return;
		}

		String markerEntry = OOXML_MARKER_ENTRY.get(extension);
		if (markerEntry != null) {
			if (!startsWith(content, ZIP_SIGNATURE) || !containsZipEntry(content, markerEntry)) {
				throw unsupported("파일 내용이 " + extension.toUpperCase() + " 형식이 아닙니다.");
			}
		}
	}

	private void rejectIfExecutable(byte[] content) {
		for (Map.Entry<String, byte[]> entry : EXECUTABLE_SIGNATURES.entrySet()) {
			if (startsWith(content, entry.getValue())) {
				throw unsupported("실행 파일은 업로드할 수 없습니다 (" + entry.getKey() + " 감지됨).");
			}
		}
	}

	private boolean startsWith(byte[] content, byte[] signature) {
		if (content.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (content[i] != signature[i]) {
				return false;
			}
		}
		return true;
	}

	private boolean containsZipEntry(byte[] content, String entryName) {
		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (entryName.equals(entry.getName())) {
					return true;
				}
			}
		} catch (IOException e) {
			return false;
		}
		return false;
	}

	private BusinessException unsupported(String detail) {
		return new BusinessException(ErrorCode.AI_UNSUPPORTED_FILE_TYPE, detail);
	}
}
