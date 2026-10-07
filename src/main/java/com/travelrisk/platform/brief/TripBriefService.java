package com.travelrisk.platform.brief;

import com.travelrisk.platform.cost.DisruptionCostService;
import com.travelrisk.platform.policy.TravelPolicyService;
import com.travelrisk.platform.service.TravelRiskService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * The plain-language brief on a trip result. Claude writes it from the same facts the page shows;
 * without a key, or when Claude is slow or fails, the built-in template says the same thing in fixed wording.
 */
@Service
public class TripBriefService {
  private static final Logger log = LoggerFactory.getLogger(TripBriefService.class);

  private final TravelRiskService travelRiskService;
  private final TravelPolicyService policyService;
  private final DisruptionCostService costService;
  private final TripBriefWriter writer;
  private final long timeoutMs;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public TripBriefService(
      TravelRiskService travelRiskService,
      TravelPolicyService policyService,
      DisruptionCostService costService,
      TripBriefWriter writer,
      @Value("${travel-risk.ai-brief.timeout-ms:15000}") long timeoutMs) {
    this.travelRiskService = travelRiskService;
    this.policyService = policyService;
    this.costService = costService;
    this.writer = writer;
    this.timeoutMs = timeoutMs;
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /** The brief for a trip, reusing the cached check. Only Claude's briefs are cached, so a fallback is retried next time. */
  @Cacheable(value = "tripBriefs", key = "{#origin, #destination, #date, #mode, #originAirport, #destinationAirport}",
      unless = "#result.source() != 'claude'")
  public TripBrief brief(String origin, String destination, String date, String mode,
      String originAirport, String destinationAirport) {
    TravelRiskService.Assessment assessment =
        travelRiskService.analyze(origin, destination, date, mode, originAirport, destinationAirport);
    return brief(facts(assessment));
  }

  TripBriefFacts facts(TravelRiskService.Assessment assessment) {
    TravelRiskService.Alternatives alternatives = null;
    try {
      alternatives = travelRiskService.alternatives(assessment);
    } catch (RuntimeException error) {
      // A past date or a forecast outage just leaves the safer options out of the brief.
      log.debug("Brief written without alternatives: {}", error.getMessage());
    }
    return new TripBriefFacts(
        assessment,
        policyService.evaluate(assessment),
        costService.estimate(assessment.score().level(), assessment.input().mode()),
        alternatives);
  }

  TripBrief brief(TripBriefFacts facts) {
    String template = TemplateTripBrief.write(facts);
    if (!writer.configured()) {
      return TripBrief.fromTemplate(template, "ANTHROPIC_API_KEY is not set, so the built-in summary is shown.");
    }
    try {
      String text = CompletableFuture.supplyAsync(() -> {
        try {
          return writer.write(facts);
        } catch (Exception error) {
          throw new IllegalStateException(error.getMessage(), error);
        }
      }, executor).get(timeoutMs, TimeUnit.MILLISECONDS);
      return TripBrief.fromClaude(text, writer.model());
    } catch (TimeoutException error) {
      log.warn("Claude brief took longer than {} ms; using the built-in summary", timeoutMs);
      return TripBrief.fromTemplate(template, "Claude took too long, so the built-in summary is shown.");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return TripBrief.fromTemplate(template, "Claude was unavailable, so the built-in summary is shown.");
    } catch (Exception error) {
      log.warn("Claude brief failed; using the built-in summary: {}", error.getMessage());
      return TripBrief.fromTemplate(template, "Claude was unavailable, so the built-in summary is shown.");
    }
  }
}
