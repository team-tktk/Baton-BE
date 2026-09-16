package com.baton.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * source_documents.extracted_text의 PostgreSQL Large Object 정리.
 *
 * @Lob String은 PostgreSQL에서 oid 컬럼 + pg_largeobject에 저장된다. Hibernate는 텍스트를 바꿀 때마다
 * 새 Large Object를 만들고, 행을 지우거나 텍스트를 바꿔도 이전 객체를 지우지 않는다(로컬에서 확인함).
 * 그대로 두면 마스킹 확정 후에도, 파일을 삭제한 후에도 원문이 DB에 남는다. 그래서 직접 lo_unlink 한다.
 *
 * 반드시 JPA와 같은 트랜잭션 안에서, 엔티티 변경을 flush한 뒤에 호출한다.
 * Large Object 삭제도 트랜잭션에 포함되므로 롤백되면 함께 취소된다.
 */
@Component
@RequiredArgsConstructor
public class LargeObjectCleaner {

	private final JdbcTemplate jdbcTemplate;

	/** 현재 원문이 저장된 Large Object 번호. 행이나 텍스트가 없으면 null. */
	public Long extractedTextOid(UUID sourceDocumentId) {
		List<Long> oids = jdbcTemplate.queryForList(
				"SELECT extracted_text FROM source_documents WHERE id = ?", Long.class, sourceDocumentId);
		return oids.isEmpty() ? null : oids.get(0);
	}

	/** 텍스트를 바꾼 뒤, 이전 객체가 더 이상 이 행에서 참조되지 않으면 지운다. */
	public void unlinkIfReplaced(UUID sourceDocumentId, Long previousOid) {
		if (previousOid != null && !previousOid.equals(extractedTextOid(sourceDocumentId))) {
			unlink(previousOid);
		}
	}

	public void unlink(Long oid) {
		if (oid != null) {
			jdbcTemplate.queryForObject("SELECT lo_unlink(?)", Integer.class, oid);
		}
	}
}
