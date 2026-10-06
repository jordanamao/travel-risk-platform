package com.travelrisk.platform.controllers;

import com.travelrisk.platform.itinerary.ItineraryImportService;
import com.travelrisk.platform.itinerary.ItinerarySamples;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ItineraryImportController {
  private static final List<String> SAMPLE_TRAVELERS =
      List.of("employee1@email.com", "employee2@email.com", "employee3@email.com");

  private final ItineraryImportService service;

  public ItineraryImportController(ItineraryImportService service) {
    this.service = service;
  }

  /** Imports a CSV or .ics itinerary: every trip is risk-checked, saved and checked against the travel policy. */
  @PostMapping("/api/trips/import")
  public ItineraryImportService.ImportResult importItinerary(
      Authentication authentication,
      @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Choose a CSV or calendar (.ics) file to import.");
    }
    return service.importFile(file.getOriginalFilename(), file.getBytes(), authentication.getName(), isAdmin(authentication));
  }

  /** A sample file dated from today. Admins get trips for several employees; everyone else gets their own. */
  @GetMapping("/api/trips/import/sample")
  public ResponseEntity<String> sample(
      Authentication authentication,
      @RequestParam(value = "format", defaultValue = "csv") String format) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<String> travelers = isAdmin(authentication) ? SAMPLE_TRAVELERS : List.of(authentication.getName());
    boolean ics = switch (format.toLowerCase()) {
      case "csv" -> false;
      case "ics" -> true;
      default -> throw new IllegalArgumentException("Format must be csv or ics.");
    };
    String body = ics ? ItinerarySamples.ics(today, travelers) : ItinerarySamples.csv(today, travelers);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(ics ? "sample-itinerary.ics" : "sample-itinerary.csv")
            .build()
            .toString())
        .contentType(ics ? MediaType.parseMediaType("text/calendar") : MediaType.parseMediaType("text/csv"))
        .body(body);
  }

  private static boolean isAdmin(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
  }
}
