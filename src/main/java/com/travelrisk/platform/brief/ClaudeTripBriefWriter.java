package com.travelrisk.platform.brief;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Asks Claude to turn a trip's facts into a short brief. Off until ANTHROPIC_API_KEY is set. */
@Component
public class ClaudeTripBriefWriter implements TripBriefWriter {
  static final String SYSTEM_PROMPT = """
      You write the trip brief at the top of a corporate travel risk check. The reader is a business \
      traveler deciding whether to keep, change or get approval for a trip. You get the check's facts \
      as JSON: the trip, its risk level and the signals behind it, the company policy result, the \
      estimated cost of disruption and any safer options that were re-scored.

      Write 3 or 4 plain sentences, under 90 words, as one paragraph with no headings, lists or markdown. \
      Say how risky the trip is and the main reason in everyday words, what company policy requires if \
      anything, what doing nothing is likely to cost when the risk is Medium or High, and the best safer \
      option if there is one, naming its date or airport. End with what the traveler should do next. \
      If some sources were unavailable, say the picture is incomplete.

      Use only the facts given. Do not invent flight numbers, delay times, weather details or amounts, \
      and do not mention points, JSON or that you are an AI.""";

  private final AnthropicClient client;
  private final ObjectMapper objectMapper;
  private final String model;

  public ClaudeTripBriefWriter(
      ObjectMapper objectMapper,
      @Value("${travel-risk.ai-brief.anthropic-api-key:}") String apiKey,
      @Value("${travel-risk.ai-brief.model:claude-opus-5-5}") String model,
      @Value("${travel-risk.ai-brief.timeout-ms:15000}") long timeoutMs) {
    this.objectMapper = objectMapper;
    this.model = model;
    this.client = apiKey == null || apiKey.isBlank() ? null : AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMillis(timeoutMs))
        .maxRetries(1)
        .build();
  }

  @Override
  public boolean configured() {
    return client != null;
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public String write(TripBriefFacts facts) throws Exception {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(2000L)
        .system(SYSTEM_PROMPT)
        .addUserMessage(objectMapper.writeValueAsString(facts.toPromptData()))
        // A short summary needs little reasoning; low effort keeps it fast and cheap.
        .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        // If a safety check declines the request, the API retries it on a fallback model instead of failing.
        .addBeta("server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .build();
    BetaMessage message = client.beta().messages().create(params);
    if (message.stopReason().map(BetaStopReason.REFUSAL::equals).orElse(false)) {
      throw new IllegalStateException("Claude declined to write this brief");
    }
    String text = message.content().stream()
        .flatMap(block -> block.text().stream())
        .map(BetaTextBlock::text)
        .collect(Collectors.joining(" "))
        .trim();
    if (text.isEmpty()) {
      throw new IllegalStateException("Claude returned an empty brief");
    }
    return text;
  }
}
