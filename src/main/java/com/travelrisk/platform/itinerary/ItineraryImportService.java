package com.travelrisk.platform.itinerary;

import com.travelrisk.platform.policy.PolicyDecision;
import com.travelrisk.platform.service.SavedTripService;
import com.travelrisk.platform.service.TravelRiskService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns a customer's itinerary file into saved, risk-checked trips.
 * Every row gets its own result, so one bad row (a past date, a typo in the trip type) never blocks the rest.
 */
@Service
public class ItineraryImportService {
  private static final Logger log = LoggerFactory.getLogger(ItineraryImportService.class);
  /** Each risk check already calls several data sources in parallel, so keep row-level parallelism small. */
  private static final int PARALLEL_CHECKS = 3;

  private final TravelRiskService travelRiskService;
  private final SavedTripService savedTripService;
  private final int maxRows;

  public ItineraryImportService(
      TravelRiskService travelRiskService,
      SavedTripService savedTripService,
      @Value("${travel-risk.itinerary-import.max-rows:25}") int maxRows) {
    this.travelRiskService = travelRiskService;
    this.savedTripService = savedTripService;
    this.maxRows = maxRows;
  }

  /**
   * @param username the signed-in user; trips with no traveler column are saved to them
   * @param admin admins may import trips for any traveler; everyone else only for themselves
   */
  public ImportResult importFile(String filename, byte[] content, String username, boolean admin) {
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("Choose a CSV or calendar (.ics) file to import.");
    }
    String text = new String(content, StandardCharsets.UTF_8);
    Format format = detectFormat(filename, text);
    List<ItineraryRow> rows = format == Format.ICS ? ItineraryIcsParser.parse(text) : ItineraryCsvParser.parse(text);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException(format == Format.ICS
          ? "The calendar file has no events to import."
          : "The CSV file has a header row but no trips.");
    }
    if (rows.size() > maxRows) {
      throw new IllegalArgumentException("Import up to " + maxRows + " trips at a time. This file has " + rows.size() + ".");
    }

    List<ItineraryRow> checked = assignTravelers(rows, username, admin);
    List<CompletableFuture<RowResult>> futures = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_CHECKS)) {
      for (ItineraryRow row : checked) {
        futures.add(row.valid()
            ? CompletableFuture.supplyAsync(() -> importRow(row), executor)
            : CompletableFuture.completedFuture(RowResult.skipped(row)));
      }
    }
    List<RowResult> results = futures.stream().map(CompletableFuture::join).toList();
    int imported = (int) results.stream().filter(result -> RowResult.IMPORTED.equals(result.status())).count();
    return new ImportResult(format.label, results.size(), imported, results.size() - imported, results);
  }

  private List<ItineraryRow> assignTravelers(List<ItineraryRow> rows, String username, boolean admin) {
    Map<String, Integer> seen = new HashMap<>();
    List<ItineraryRow> result = new ArrayList<>();
    for (ItineraryRow row : rows) {
      ItineraryRow current = row;
      if (current.valid()) {
        if (current.traveler().isEmpty()) {
          current = current.withTraveler(username);
        } else if (!admin && !current.traveler().equalsIgnoreCase(username)) {
          current = current.withError("This trip is for " + current.traveler()
              + ". Only an admin can import trips for other travelers.");
        }
      }
      if (current.valid()) {
        String key = String.join("|", current.traveler().toLowerCase(Locale.ROOT), current.origin(),
            current.destination(), current.date(), current.mode(), current.originAirport(), current.destinationAirport());
        Integer firstLine = seen.putIfAbsent(key, current.line());
        if (firstLine != null) {
          current = current.withError("Same trip as line " + firstLine + ".");
        }
      }
      result.add(current);
    }
    return result;
  }

  private RowResult importRow(ItineraryRow row) {
    try {
      TravelRiskService.Assessment assessment = travelRiskService.analyze(
          row.origin(), row.destination(), row.date(), row.mode(), row.originAirport(), row.destinationAirport());
      SavedTripService.SavedTripResponse saved = savedTripService.save(row.traveler(), assessment);
      return new RowResult(row.line(), row.traveler(), saved.origin(), saved.destination(), saved.date(), saved.mode(),
          RowResult.IMPORTED, null, saved.id(), saved.riskLevel(), saved.riskPoints(), saved.policy());
    } catch (IllegalArgumentException error) {
      return RowResult.skipped(row.withError(error.getMessage()));
    } catch (RuntimeException error) {
      log.warn("Itinerary import could not check line {} ({} to {})", row.line(), row.origin(), row.destination(), error);
      return RowResult.skipped(row.withError("The risk check failed for this trip. Try importing it again in a minute."));
    }
  }

  private static Format detectFormat(String filename, String text) {
    String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (name.endsWith(".ics") || name.endsWith(".ical") || name.endsWith(".ifb")) {
      return Format.ICS;
    }
    if (name.endsWith(".csv")) {
      return Format.CSV;
    }
    return text.stripLeading().replace("﻿", "").toUpperCase(Locale.ROOT).startsWith("BEGIN:VCALENDAR")
        ? Format.ICS
        : Format.CSV;
  }

  private enum Format {
    CSV("csv"),
    ICS("ics");

    private final String label;

    Format(String label) {
      this.label = label;
    }
  }

  public record ImportResult(String format, int totalRows, int imported, int skipped, List<RowResult> rows) {}

  public record RowResult(
      int line,
      String traveler,
      String origin,
      String destination,
      String date,
      String mode,
      String status,
      String error,
      Long tripId,
      String riskLevel,
      Integer riskPoints,
      PolicyDecision policy) {
    static final String IMPORTED = "imported";
    static final String SKIPPED = "skipped";

    static RowResult skipped(ItineraryRow row) {
      return new RowResult(row.line(), row.traveler(), row.origin(), row.destination(), row.date(), row.mode(),
          SKIPPED, row.error(), null, null, null, null);
    }
  }
}
