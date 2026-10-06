package com.travelrisk.platform.repository;

import com.travelrisk.platform.database.entities.AssessmentHistory;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentHistoryRepository extends JpaRepository<AssessmentHistory, Long> {
  List<AssessmentHistory> findTop50ByOrderByCreatedAtDesc();

  long countByRiskLevelIgnoreCaseAndCreatedAtGreaterThanEqual(String riskLevel, Instant createdAt);

  List<AssessmentHistory> findByAssessmentJsonContaining(String text);
}
