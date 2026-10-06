package com.travelrisk.platform.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItineraryParserTest {

  @Test
  void csvHandlesQuotedCitiesHeaderAliasesAndUsDates() {
    List<ItineraryRow> rows = ItineraryCsvParser.parse("""
        ﻿Traveler Email,From,To,Travel Date,Mode,From Airport,To Airport\r
        Employee1@Email.com,"Denver, CO","Seattle, WA",10/09/2026,Fly,den,KSEA\r
        \r
        ,"Boston, MA","Washington, DC",2026-10-11,driving,,\r
        """);

    assertThat(rows).hasSize(2);
    ItineraryRow first = rows.get(0);
    assertThat(first.valid()).isTrue();
    assertThat(first.line()).isEqualTo(2);
    assertThat(first.traveler()).isEqualTo("employee1@email.com");
    assertThat(first.origin()).isEqualTo("Denver, CO");
    assertThat(first.date()).isEqualTo("2026-10-09");
    assertThat(first.mode()).isEqualTo("flight");
    assertThat(first.originAirport()).isEqualTo("KDEN");
    assertThat(first.destinationAirport()).isEqualTo("KSEA");
    assertThat(rows.get(1).line()).isEqualTo(4);
    assertThat(rows.get(1).mode()).isEqualTo("drive");
    assertThat(rows.get(1).traveler()).isEmpty();
  }

  @Test
  void csvRowProblemsAreRecordedPerRow() {
    List<ItineraryRow> rows = ItineraryCsvParser.parse("""
        origin,destination,date,trip_type,traveler_email
        "New York, NY",,2026-10-09,flight,
        "New York, NY","Chicago, IL",next week,flight,
        "New York, NY","Chicago, IL",2026-10-09,boat,
        "New York, NY","Chicago, IL",2026-10-09,flight,not-an-email
        """);

    assertThat(rows).extracting(ItineraryRow::error).containsExactly(
        "Destination is missing.",
        "Date 'next week' isn't a date. Use YYYY-MM-DD or MM/DD/YYYY.",
        "Trip type 'boat' isn't supported. Use flight, drive or general.",
        "Traveler 'not-an-email' isn't an email address.");
  }

  @Test
  void csvWithoutRequiredColumnsIsRejected() {
    assertThatThrownBy(() -> ItineraryCsvParser.parse("origin,when\nA,2026-10-09\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("The CSV file is missing the destination, date columns.");
    assertThatThrownBy(() -> ItineraryCsvParser.parse("origin,destination,date\n\"Denver, CO,Seattle,2026-10-09\n"))
        .hasMessage("The CSV file has an unclosed quote starting on line 2.");
  }

  @Test
  void icsReadsRouteDateTravelerAndAirportsFromEachEvent() {
    List<ItineraryRow> rows = ItineraryIcsParser.parse("""
        BEGIN:VCALENDAR\r
        VERSION:2.0\r
        BEGIN:VEVENT\r
        DTSTART;TZID=America/New_York:20261008T090000\r
        SUMMARY:Flight: New York\\, NY (JFK) to Chic\r
         ago\\, IL (ORD)\r
        ATTENDEE;CN="Doe, Jane";ROLE=REQ-PARTICIPANT:mailto:jane@acme.com\r
        END:VEVENT\r
        BEGIN:VEVENT\r
        DTSTART;VALUE=DATE:20261011\r
        SUMMARY:Team offsite\r
        X-TRAVEL-ORIGIN:Boston\\, MA\r
        X-TRAVEL-DESTINATION:Washington\\, DC\r
        X-TRAVEL-MODE:drive\r
        END:VEVENT\r
        BEGIN:VEVENT\r
        DTSTART:20261012T140000Z\r
        SUMMARY:Dentist\r
        END:VEVENT\r
        END:VCALENDAR\r
        """);

    assertThat(rows).hasSize(3);
    ItineraryRow flight = rows.get(0);
    assertThat(flight.valid()).isTrue();
    assertThat(flight.line()).isEqualTo(3);
    assertThat(flight.origin()).isEqualTo("New York, NY");
    assertThat(flight.destination()).isEqualTo("Chicago, IL");
    assertThat(flight.originAirport()).isEqualTo("KJFK");
    assertThat(flight.destinationAirport()).isEqualTo("KORD");
    assertThat(flight.date()).isEqualTo("2026-10-08");
    assertThat(flight.traveler()).isEqualTo("jane@acme.com");

    ItineraryRow drive = rows.get(1);
    assertThat(drive.origin()).isEqualTo("Boston, MA");
    assertThat(drive.mode()).isEqualTo("drive");

    assertThat(rows.get(2).valid()).isFalse();
    assertThat(rows.get(2).error()).startsWith("Couldn't read a route from the event title 'Dentist'.");
  }

  @Test
  void icsWithoutCalendarIsRejected() {
    assertThatThrownBy(() -> ItineraryIcsParser.parse("hello"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The calendar file is not valid. It should start with BEGIN:VCALENDAR.");
  }

  @Test
  void samplesParseCleanly() {
    LocalDate today = LocalDate.of(2026, 10, 6);
    List<String> travelers = List.of("employee1@email.com", "employee2@email.com");

    List<ItineraryRow> csv = ItineraryCsvParser.parse(ItinerarySamples.csv(today, travelers));
    List<ItineraryRow> ics = ItineraryIcsParser.parse(ItinerarySamples.ics(today, travelers));

    assertThat(csv).hasSize(5).allMatch(ItineraryRow::valid);
    assertThat(ics).usingRecursiveFieldByFieldElementComparatorIgnoringFields("line").isEqualTo(csv);
  }
}
