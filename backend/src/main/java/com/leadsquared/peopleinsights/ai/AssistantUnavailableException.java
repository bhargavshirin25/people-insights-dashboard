package com.leadsquared.peopleinsights.ai;

/**
 * The assistant could not answer for a reason that is not the reader's fault: no API key, a timeout,
 * or an error from the model.
 *
 * <p>Separate from a refusal, which is an answer of a kind and comes back through the normal path. This
 * one becomes a 503 so the chat panel can say the assistant is unavailable rather than showing an empty
 * reply that reads like an answer.
 */
public class AssistantUnavailableException extends RuntimeException {

  public AssistantUnavailableException(String message) {
    super(message);
  }
}
