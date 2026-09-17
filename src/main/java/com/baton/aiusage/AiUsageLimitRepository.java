package com.baton.aiusage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiUsageLimitRepository extends JpaRepository<AiUsageLimit, AiUsageScope> {
}
