package com.leadsquared.peopleinsights.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claude API settings for the narrative summaries and the dashboard assistant.
 *
 * <p>A blank {@code apiKey} is a supported state: {@code NarrativeService} then emits a
 * deterministic template narrative built from the same metric figures. The view still renders and
 * still cites only real numbers — it just loses the prose quality. The assistant has no equivalent
 * fallback — a chat reply cannot be assembled from a template — so it reports itself unavailable
 * rather than answering from nothing.
 *
 * <p>The assistant carries its own model, token ceiling and timeout because the two callers want
 * opposite things. A narrative is one short paragraph written once per view and cached against a
 * figure fingerprint; a chat turn is interactive, arrives with up to fifty prior messages behind it,
 * and is never cached. Sharing one {@code max-tokens} would either truncate replies or make every
 * narrative pay for headroom it does not use.
 */
@ConfigurationProperties(prefix = "claude")
public class ClaudeProperties {

  private String apiKey = "";
  private String baseUrl = "https://api.anthropic.com";
  private String model = "claude-sonnet-4-5";
  private int maxTokens = 700;
  private int timeoutSeconds = 45;
  private String anthropicVersion = "2023-06-01";

  private String chatModel = "claude-opus-5";
  private int chatMaxTokens = 8000;
  private String chatEffort = "medium";
  private int chatTimeoutSeconds = 90;
  private int chatHistoryMessages = 100;
  private int chatHistoryChars = 40_000;

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
  }

  public int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  public void setTimeoutSeconds(int timeoutSeconds) {
    this.timeoutSeconds = timeoutSeconds;
  }

  public String getAnthropicVersion() {
    return anthropicVersion;
  }

  public void setAnthropicVersion(String anthropicVersion) {
    this.anthropicVersion = anthropicVersion;
  }

  public String getChatModel() {
    return chatModel;
  }

  public void setChatModel(String chatModel) {
    this.chatModel = chatModel;
  }

  public int getChatMaxTokens() {
    return chatMaxTokens;
  }

  public void setChatMaxTokens(int chatMaxTokens) {
    this.chatMaxTokens = chatMaxTokens;
  }

  /** Reasoning depth for the assistant: low | medium | high | xhigh | max. */
  public String getChatEffort() {
    return chatEffort;
  }

  public void setChatEffort(String chatEffort) {
    this.chatEffort = chatEffort;
  }

  public int getChatTimeoutSeconds() {
    return chatTimeoutSeconds;
  }

  public void setChatTimeoutSeconds(int chatTimeoutSeconds) {
    this.chatTimeoutSeconds = chatTimeoutSeconds;
  }

  /**
   * How many prior messages of a conversation are sent back as context — user and assistant turns
   * counted together, so 100 is fifty exchanges.
   */
  public int getChatHistoryMessages() {
    return chatHistoryMessages;
  }

  public void setChatHistoryMessages(int chatHistoryMessages) {
    this.chatHistoryMessages = chatHistoryMessages;
  }

  /**
   * Character ceiling on the history, applied after the message count.
   *
   * <p>A message limit alone does not bound the request: fifty exchanges of pasted text would be
   * several hundred thousand characters. This trims the oldest turns until the transcript fits,
   * which keeps a long conversation working rather than failing at the context window.
   */
  public int getChatHistoryChars() {
    return chatHistoryChars;
  }

  public void setChatHistoryChars(int chatHistoryChars) {
    this.chatHistoryChars = chatHistoryChars;
  }
}
