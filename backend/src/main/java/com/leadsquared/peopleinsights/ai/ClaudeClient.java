package com.leadsquared.peopleinsights.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadsquared.peopleinsights.config.ClaudeProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal Claude Messages API client, with a single-turn call for the narrative and a multi-turn call
 * for the dashboard assistant.
 *
 * <p>Deliberately thin: no streaming, no retries beyond the HTTP client's own. The narrative is a page
 * element with a deterministic fallback, so a slow or failed call must degrade the page rather than
 * block it — which is why the timeout is short and every failure returns empty rather than
 * propagating. The assistant has no such fallback, so it reports why it could not answer instead.
 *
 * <p>This talks to {@code /v1/messages} over plain HTTP rather than through the Anthropic Java SDK
 * because the narrative client already did and the assistant needs the same two headers and the same
 * failure handling. Adding an SDK dependency for a second caller would leave two ways of reaching one
 * endpoint in one application.
 */
@Component
public class ClaudeClient {

  private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

  /**
   * Model families that accept {@code output_config.effort} and server-side {@code fallbacks}.
   *
   * <p>Both are model-gated, and sending either to a model that does not support it is a 400 rather
   * than a silently ignored field. Checking the configured model here means {@code claude.chat-model}
   * can be pointed at an older model without also having to remember to unset the two knobs.
   */
  private static final List<String> EFFORT_AND_FALLBACK_MODELS =
      List.of("claude-opus-5", "claude-fable-5", "claude-mythos-5");

  /**
   * Routes a request the safety classifiers decline to Anthropic's recommended substitute model
   * instead of returning the refusal, chosen by refusal category.
   */
  private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

  private final ClaudeProperties props;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http;

  public ClaudeClient(ClaudeProperties props) {
    this.props = props;
    this.http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public boolean isConfigured() {
    return props.isConfigured();
  }

  public String model() {
    return props.getModel();
  }

  public String chatModel() {
    return props.getChatModel();
  }

  /**
   * Sends a single-turn request.
   *
   * @return the assistant's text, or empty when the model is unconfigured or the call fails
   */
  public Optional<String> complete(String systemPrompt, String userPrompt) {
    if (!props.isConfigured()) {
      return Optional.empty();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", props.getModel());
    body.put("max_tokens", props.getMaxTokens());
    body.put("system", systemPrompt);
    body.put(
        "messages",
        List.of(Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", userPrompt)))));

    return send(body, props.getTimeoutSeconds(), null)
        .map(ClaudeClient::textOf)
        .filter(text -> !text.isEmpty());
  }

  /** One conversation turn. {@code role} is {@code user} or {@code assistant}. */
  public record Turn(String role, String text) {}

  public enum ChatStatus {
    OK,
    /** No API key, so there is nothing to ask. */
    NOT_CONFIGURED,
    /** The model declined to answer. */
    REFUSED,
    /** Timed out, errored or returned nothing usable. */
    FAILED
  }

  /**
   * @param truncated the reply hit the token ceiling and is cut off mid-thought
   */
  public record ChatReply(ChatStatus status, String text, String model, boolean truncated) {

    static ChatReply of(ChatStatus status, String model) {
      return new ChatReply(status, null, model, false);
    }
  }

  /**
   * Sends a conversation and returns the next assistant turn.
   *
   * <p>The system prompt is split in two on purpose. {@code instructions} is fixed for every request;
   * {@code grounding} is the dashboard brief, which changes only when the business unit or the filters
   * change. Marking the end of the pair as cacheable means a conversation that keeps asking about the
   * same view re-reads the brief from cache instead of paying for it on every turn — and the split is
   * also what keeps the cacheable prefix stable, since anything appended after it (the turns
   * themselves) cannot invalidate it.
   *
   * @param turns the conversation so far, oldest first, ending on the user's new message
   */
  public ChatReply chat(String instructions, String grounding, List<Turn> turns) {
    String model = props.getChatModel();
    if (!props.isConfigured()) {
      return ChatReply.of(ChatStatus.NOT_CONFIGURED, model);
    }
    if (turns == null || turns.isEmpty()) {
      throw new IllegalArgumentException("A chat request needs at least one message.");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("max_tokens", props.getChatMaxTokens());
    body.put(
        "system",
        List.of(
            Map.of("type", "text", "text", instructions),
            Map.of(
                "type", "text",
                "text", grounding,
                "cache_control", Map.of("type", "ephemeral"))));

    List<Map<String, Object>> messages = new ArrayList<>();
    for (Turn turn : turns) {
      messages.add(
          Map.of(
              "role", turn.role(),
              "content", List.of(Map.of("type", "text", "text", turn.text()))));
    }
    body.put("messages", messages);

    String beta = null;
    if (supportsEffortAndFallbacks(model)) {
      body.put("output_config", Map.of("effort", props.getChatEffort()));
      body.put("fallbacks", "default");
      beta = FALLBACK_BETA;
    }

    Optional<JsonNode> response = send(body, props.getChatTimeoutSeconds(), beta);
    if (response.isEmpty()) {
      return ChatReply.of(ChatStatus.FAILED, model);
    }

    JsonNode root = response.get();
    // Cache reads are the only way to tell the brief is being reused across a conversation rather than
    // re-read on every turn, and they are the difference in what a long conversation costs.
    JsonNode usage = root.path("usage");
    log.debug(
        "Assistant usage — input {}, cache written {}, cache read {}, output {}",
        usage.path("input_tokens").asInt(),
        usage.path("cache_creation_input_tokens").asInt(),
        usage.path("cache_read_input_tokens").asInt(),
        usage.path("output_tokens").asInt());

    String stopReason = root.path("stop_reason").asText("");
    // A declined request is a successful HTTP 200 with no usable content, so the stop reason has to be
    // read before the content blocks rather than after finding them empty.
    if ("refusal".equals(stopReason)) {
      log.info(
          "Assistant request declined by the model ({})",
          root.path("stop_details").path("category").asText("unspecified"));
      return ChatReply.of(ChatStatus.REFUSED, model);
    }

    String text = textOf(root);
    if (text.isEmpty()) {
      return ChatReply.of(ChatStatus.FAILED, model);
    }
    // The model that actually answered, which is not the requested one when a fallback served it.
    String answeringModel = root.path("model").asText(model);
    return new ChatReply(ChatStatus.OK, text, answeringModel, "max_tokens".equals(stopReason));
  }

  /**
   * Posts a request body and returns the parsed response, or empty on any failure.
   *
   * @param beta value for the {@code anthropic-beta} header, or null to omit it
   */
  private Optional<JsonNode> send(Map<String, Object> body, int timeoutSeconds, String beta) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(props.getBaseUrl() + "/v1/messages"))
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .header("content-type", "application/json")
              .header("x-api-key", props.getApiKey())
              .header("anthropic-version", props.getAnthropicVersion());
      if (beta != null) {
        request.header("anthropic-beta", beta);
      }

      HttpResponse<String> response =
          http.send(
              request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
              HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() / 100 != 2) {
        log.warn("Claude API returned {}: {}", response.statusCode(), truncate(response.body()));
        return Optional.empty();
      }
      return Optional.of(mapper.readTree(response.body()));

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Claude call interrupted");
      return Optional.empty();
    } catch (Exception e) {
      log.warn("Claude call failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /** The text blocks of a response, concatenated. Thinking blocks carry no text and are skipped. */
  private static String textOf(JsonNode root) {
    StringBuilder text = new StringBuilder();
    for (JsonNode block : root.path("content")) {
      if ("text".equals(block.path("type").asText())) {
        text.append(block.path("text").asText());
      }
    }
    return text.toString().trim();
  }

  private static boolean supportsEffortAndFallbacks(String model) {
    return model != null && EFFORT_AND_FALLBACK_MODELS.stream().anyMatch(model::startsWith);
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 400 ? s : s.substring(0, 400) + "…";
  }
}
