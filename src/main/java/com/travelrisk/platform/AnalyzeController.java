package com.travelrisk.platform;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyzeController {
  private final TravelRiskService service;

  public AnalyzeController(TravelRiskService service) {
    this.service = service;
  }

  @GetMapping("/api/analyze")
  public TravelRiskService.Assessment analyze(
      @RequestParam String origin,
      @RequestParam String destination,
      @RequestParam String date,
      @RequestParam(defaultValue = "flight") String mode,
      @RequestParam(defaultValue = "") String originAirport,
      @RequestParam(defaultValue = "") String destinationAirport) {
    return service.analyze(origin, destination, date, mode, originAirport, destinationAirport);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> serverError(Exception error) {
    String message = error.getMessage() != null && error.getMessage().contains("429")
        ? "External geocoding service is rate-limiting requests. Try a listed city or wait a moment before retrying."
        : "Unexpected server error";
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", message, "details", error.getMessage() == null ? "" : error.getMessage()));
  }
}
