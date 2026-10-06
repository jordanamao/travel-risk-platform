package com.travelrisk.platform.repository;

import com.travelrisk.platform.database.entities.AssessmentHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentHistoryRepository extends JpaRepository<AssessmentHistory, Long> {
  List<AssessmentHistory> findTop10ByOrderByCreatedAtDesc();

  List<AssessmentHistory> findByAssessmentJsonContaining(String text);
}
