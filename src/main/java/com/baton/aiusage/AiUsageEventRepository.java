package com.baton.aiusage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUsageEventRepository extends JpaRepository<AiUsageEvent, UUID> {

	@Query("""
			select new com.baton.aiusage.AiUsageCounts(
				sum(case when e.createdAt > :minuteAgo then 1 else 0 end),
				sum(case when e.createdAt > :hourAgo then 1 else 0 end),
				count(e))
			from AiUsageEvent e
			where e.userId = :userId and e.createdAt > :dayAgo
			""")
	AiUsageCounts countByUser(@Param("userId") UUID userId, @Param("minuteAgo") Instant minuteAgo, @Param("hourAgo") Instant hourAgo, @Param("dayAgo") Instant dayAgo);

	@Query("""
			select new com.baton.aiusage.AiUsageCounts(
				sum(case when e.createdAt > :minuteAgo then 1 else 0 end),
				sum(case when e.createdAt > :hourAgo then 1 else 0 end),
				count(e))
			from AiUsageEvent e
			where e.organizationKey = :organizationKey and e.createdAt > :dayAgo
			""")
	AiUsageCounts countByOrganization(@Param("organizationKey") String organizationKey,
			@Param("minuteAgo") Instant minuteAgo,
			@Param("hourAgo") Instant hourAgo, @Param("dayAgo") Instant dayAgo);

	/** since 이후 요청 시각을 최신순으로. Pageable로 n번째 최신 요청 하나만 꺼낸다(다시 가능한 시각 계산용). */
	@Query("""
			select e.createdAt from AiUsageEvent e
			where e.userId = :userId and e.createdAt > :since
			order by e.createdAt desc
			""")
	List<Instant> findRecentByUser(@Param("userId") UUID userId, @Param("since") Instant since, Pageable pageable);

	@Query("""
			select e.createdAt from AiUsageEvent e
			where e.organizationKey = :organizationKey and e.createdAt > :since
			order by e.createdAt desc
			""")
	List<Instant> findRecentByOrganization(@Param("organizationKey") String organizationKey,
			@Param("since") Instant since, Pageable pageable);

	@Modifying
	@Query("delete from AiUsageEvent e where e.createdAt < :threshold")
	int deleteOlderThan(@Param("threshold") Instant threshold);
}
