package com.baton.handover;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 인수인계 저장소.
 *
 * 참여자/업무범위는 서비스의 트랜잭션 안에서 lazy 로딩해 DTO로 매핑한다.
 * (두 List 컬렉션을 한 쿼리로 동시 fetch하면 Hibernate MultipleBagFetchException이 나므로
 *  @EntityGraph로 함께 끌어오지 않는다.)
 */
public interface HandoverRepository extends JpaRepository<Handover, UUID> {
}
