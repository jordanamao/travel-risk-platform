package com.travelrisk.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssessmentHistoryService {
  private final AssessmentHistoryRepository repository;
  private final ObjectMapper objectMapper;

  public AssessmentHistoryService(AssessmentHistoryRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public AssessmentHistory record(String username, TravelRiskService.Assessment assessment) {
    return repository.save(new AssessmentHistory(username, assessment, writeAssessment(assessment)));
  }

  private String writeAssessment(TravelRiskService.Assessment assessment) {
    try {
      return objectMapper.writeValueAsString(assessment);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Could not save assessment history", error);
    }
  }
}
