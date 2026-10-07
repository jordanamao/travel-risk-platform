package com.travelrisk.platform.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.TestPolicies;
import com.travelrisk.platform.cost.DisruptionCostService;
import com.travelrisk.platform.cost.DisruptionCostSettings;
import com.travelrisk.platform.service.TravelRiskService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TripBriefServiceTest {
  private final TravelRiskService travelRiskService = mock(TravelRiskService.class);
  private final DisruptionCostService costService = new DisruptionCostService(DisruptionCostSettings.defaults());

  private TripBriefService service(TripBriefWriter writer, long timeoutMs) {
    return new TripBriefService(travelRiskService, TestPolicies.defaultPolicy(), costService, writer, timeoutMs);
  }

  private TripBriefFacts highRiskFlightWithSaferOption() {
    var assessment = TestAssessments.assessment("New York, NY", "Chicago, IL", "2026-10-09", "flight", "High", 14);
    assessment.signals().getFirst().points(7);
    var later = new TravelRiskService.Alternative("later-day", "Leave a day later", "Sat, Oct 10",
        new TravelRiskService.Input("New York, NY", "Chicago, IL", "2026-10-10", "flight", "", ""),
        new TravelRiskService.Score(3, "Low", "medium"), 11, List.of("Chicago, IL: rain chance 90% → 10%"), true);
    when(travelRiskService.alternatives(assessment))
        .thenReturn(new TravelRiskService.Alternatives(assessment.score(), List.of(later), 5, null));
    return service(new FakeWriter(null, null), 1000).facts(assessment);
  }

  @Test
  void withoutAKeyTheTemplateCoversRiskPolicyCostAndTheSaferOption() {
    TripBrief brief = service(new FakeWriter(null, null), 1000).brief(highRiskFlightWithSaferOption());

    assertThat(brief.source()).isEqualTo(TripBrief.TEMPLATE);
    assertThat(brief.model()).isNull();
    assertThat(brief.note()).contains("ANTHROPIC_API_KEY");
    assertThat(brief.text())
        .startsWith("Your flight from New York, NY to Chicago, IL on Fri, Oct 9 is High risk. Main concern: Airport weather.")
        .contains("Company policy: Needs approval. High risk trips need manager approval before booking.")
        .contains("expected disruption cost of about $590 ($1,170 if it is disrupted)")
        .contains("Best safer option: leave a day later (Sat, Oct 10), which brings it down to Low.");
  }

  @Test
  void claudeWritesTheBriefFromThePageFacts() {
    TripBriefFacts facts = highRiskFlightWithSaferOption();
    FakeWriter writer = new FakeWriter("Claude's brief.", null);

    TripBrief brief = service(writer, 1000).brief(facts);

    assertThat(brief).isEqualTo(new TripBrief("Claude's brief.", TripBrief.CLAUDE, "claude-test", null));
    Map<String, Object> sent = writer.received.get().toPromptData();
    assertThat(sent).containsKeys("trip", "risk", "drivers", "companyPolicy", "costOfDoingNothing", "saferOptions");
    assertThat(sent.get("costOfDoingNothing")).isEqualTo(
        Map.of("currency", "USD", "expected", 590, "ifDisrupted", 1170, "assumedChanceOfDisruption", 0.5));
    assertThat(sent.get("saferOptions").toString()).contains("Leave a day later", "Sat, Oct 10", "newLevel=Low");
  }

  @Test
  void aFailedCallFallsBackToTheTemplate() {
    TripBrief brief = service(new FakeWriter(null, new IllegalStateException("overloaded")), 1000)
        .brief(highRiskFlightWithSaferOption());

    assertThat(brief.source()).isEqualTo(TripBrief.TEMPLATE);
    assertThat(brief.note()).isEqualTo("Claude was unavailable, so the built-in summary is shown.");
    assertThat(brief.text()).contains("High risk");
  }

  @Test
  void aSlowCallFallsBackToTheTemplate() {
    TripBriefWriter slow = new FakeWriter("too late", null) {
      @Override
      public String write(TripBriefFacts facts) throws Exception {
        Thread.sleep(2000);
        return super.write(facts);
      }
    };

    TripBrief brief = service(slow, 50).brief(highRiskFlightWithSaferOption());

    assertThat(brief.source()).isEqualTo(TripBrief.TEMPLATE);
    assertThat(brief.note()).isEqualTo("Claude took too long, so the built-in summary is shown.");
  }

  @Test
  void lowRiskTripWithNoDriversNeedsNoChange() {
    var assessment = TestAssessments.assessment("Seattle, WA", "Portland, OR", "2026-10-09", "drive", "Low", 0);
    when(travelRiskService.alternatives(assessment)).thenReturn(
        new TravelRiskService.Alternatives(assessment.score(), List.of(), 0, "This trip is already low risk."));
    TripBriefService service = service(new FakeWriter(null, null), 1000);

    TripBrief brief = service.brief(service.facts(assessment));

    assertThat(brief.text()).isEqualTo("Your drive from Seattle, WA to Portland, OR on Fri, Oct 9 is Low risk, "
        + "with no major warning signs in the current data. No change is needed; recheck conditions before you leave.");
  }

  @Test
  void alternativesThatCannotBeCheckedAreLeftOut() {
    var assessment = TestAssessments.assessment("New York, NY", "Chicago, IL", "2026-10-09", "flight", "Medium", 6);
    when(travelRiskService.alternatives(assessment)).thenThrow(new IllegalArgumentException("date is in the past"));

    TripBriefFacts facts = service(new FakeWriter(null, null), 1000).facts(assessment);

    assertThat(facts.alternatives()).isNull();
    assertThat(facts.toPromptData()).doesNotContainKey("saferOptions");
  }

  private static class FakeWriter implements TripBriefWriter {
    private final String text;
    private final Exception failure;
    final AtomicReference<TripBriefFacts> received = new AtomicReference<>();

    FakeWriter(String text, Exception failure) {
      this.text = text;
      this.failure = failure;
    }

    @Override
    public boolean configured() {
      return text != null || failure != null;
    }

    @Override
    public String model() {
      return "claude-test";
    }

    @Override
    public String write(TripBriefFacts facts) throws Exception {
      received.set(facts);
      if (failure != null) throw failure;
      return text;
    }
  }
}
