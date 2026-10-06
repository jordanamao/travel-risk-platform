package com.travelrisk.platform.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.policy.PolicyDecision;
import com.travelrisk.platform.service.SavedTripService;
import com.travelrisk.platform.service.TravelRiskService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ItineraryImportServiceTest {
  @Mock
  private TravelRiskService travelRiskService;

  @Mock
  private SavedTripService savedTripService;

  private ItineraryImportService service;

  @BeforeEach
  void setUp() {
    service = new ItineraryImportService(travelRiskService, savedTripService, 3);
  }

  @Test
  void importsValidRowsAndReportsEveryOtherRowWithItsReason() {
    var assessment = TestAssessments.assessment("New York, NY", "Chicago, IL", "2026-10-08", "flight", "High", 10);
    when(travelRiskService.analyze("New York, NY", "Chicago, IL", "2026-10-08", "flight", "KJFK", "KORD"))
        .thenReturn(assessment);
    when(travelRiskService.analyze(eq("Dallas, TX"), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalArgumentException("Travel date must be today or later."));
    PolicyDecision approval = new PolicyDecision("approval_required", "Needs approval", List.of());
    when(savedTripService.save("employee1@email.com", assessment)).thenReturn(saved(7L, approval));

    ItineraryImportService.ImportResult result = service.importFile("bookings.csv", bytes("""
        traveler_email,origin,destination,date,trip_type,origin_airport,destination_airport
        ,"New York, NY","Chicago, IL",2026-10-08,flight,JFK,ORD
        ,"Dallas, TX","Austin, TX",2025-01-01,drive,,
        employee9@email.com,"Miami, FL","Atlanta, GA",2026-10-09,general,,
        """), "employee1@email.com", false);

    assertThat(result.format()).isEqualTo("csv");
    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(2);
    assertThat(result.rows()).extracting(ItineraryImportService.RowResult::status)
        .containsExactly("imported", "skipped", "skipped");
    assertThat(result.rows().get(0).tripId()).isEqualTo(7L);
    assertThat(result.rows().get(0).policy().outcome()).isEqualTo("approval_required");
    assertThat(result.rows().get(1).error()).isEqualTo("Travel date must be today or later.");
    assertThat(result.rows().get(2).error())
        .isEqualTo("This trip is for employee9@email.com. Only an admin can import trips for other travelers.");
    verify(travelRiskService, never()).analyze(eq("Miami, FL"), any(), any(), any(), any(), any());
  }

  @Test
  void adminImportsTripsForEachTravelerAndSkipsDuplicates() {
    var assessment = TestAssessments.assessment("Denver, CO", "Seattle, WA", "2026-10-09", "flight", "Low", 1);
    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(assessment);
    when(savedTripService.save(eq("jane@acme.com"), any())).thenReturn(saved(1L, null));

    ItineraryImportService.ImportResult result = service.importFile("trips.ics", bytes("""
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261009
        SUMMARY:Flight: Denver\\, CO to Seattle\\, WA
        ATTENDEE:mailto:jane@acme.com
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261009
        SUMMARY:Flight: Denver\\, CO to Seattle\\, WA
        ATTENDEE:mailto:Jane@Acme.com
        END:VEVENT
        END:VCALENDAR
        """), "admin@email.com", true);

    assertThat(result.format()).isEqualTo("ics");
    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.rows().get(1).error()).isEqualTo("Same trip as line 2.");
  }

  @Test
  void unexpectedRiskCheckFailureSkipsOnlyThatRow() {
    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("upstream timeout"));

    ItineraryImportService.ImportResult result = service.importFile("a.csv",
        bytes("origin,destination,date\n\"New York, NY\",\"Chicago, IL\",2026-10-08\n"), "employee1@email.com", false);

    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.rows().get(0).error()).isEqualTo("The risk check failed for this trip. Try importing it again in a minute.");
  }

  @Test
  void rejectsEmptyAndOversizedFilesBeforeCheckingAnything() {
    assertThatThrownBy(() -> service.importFile("a.csv", new byte[0], "employee1@email.com", false))
        .hasMessage("Choose a CSV or calendar (.ics) file to import.");
    assertThatThrownBy(() -> service.importFile("a.csv", bytes("origin,destination,date\n"), "employee1@email.com", false))
        .hasMessage("The CSV file has a header row but no trips.");
    assertThatThrownBy(() -> service.importFile("a.csv", bytes("""
        origin,destination,date
        A,B,2026-10-08
        A,C,2026-10-08
        A,D,2026-10-08
        A,E,2026-10-08
        """), "employee1@email.com", false))
        .hasMessage("Import up to 3 trips at a time. This file has 4.");
    verify(travelRiskService, never()).analyze(any(), any(), any(), any(), any(), any());
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static SavedTripService.SavedTripResponse saved(Long id, PolicyDecision policy) {
    return new SavedTripService.SavedTripResponse(id, "New York, NY", "Chicago, IL", "2026-10-08", "flight", "KJFK", "KORD",
        "High", 10, "High disruption risk", "2026-10-06T00:00:00Z", "2026-10-06T00:00:00Z", policy, null);
  }
}
