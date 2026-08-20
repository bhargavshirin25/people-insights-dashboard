package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.ai.ChatService;
import com.leadsquared.peopleinsights.ai.ClaudeClient.Turn;
import com.leadsquared.peopleinsights.metrics.DatasetLoader;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard assistant.
 *
 * <p>A question is a data read, so it goes through {@link ScopeGuard} exactly as a view does: the same
 * business-unit resolution, the same audit entry, the same refusal for a business unit outside the
 * caller's assignment and for the Admin role, which has no employee-data access at all. Nothing here
 * takes a business unit from the request and hands it to a query — the guard returns the only list the
 * dataset loader will accept.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

  private final ScopeGuard guard;
  private final DatasetLoader loader;
  private final ChatService chat;

  public ChatController(ScopeGuard guard, DatasetLoader loader, ChatService chat) {
    this.guard = guard;
    this.loader = loader;
    this.chat = chat;
  }

  /**
   * @param message the reader's new question
   * @param history the conversation so far, oldest first, as the browser holds it
   */
  public record AskRequest(String message, List<Message> history) {

    /** {@code role} is {@code user} or {@code assistant}; anything else is discarded. */
    public record Message(String role, String text) {}
  }

  /**
   * Whether the panel should offer to answer at all.
   *
   * <p>A blank API key is a supported configuration, so the panel asks first rather than presenting an
   * input that can only fail. This deliberately does not resolve a business unit: it reports a service
   * state, reads no employee data, and so writes no audit entry.
   */
  @GetMapping("/availability")
  public Availability availability() {
    return new Availability(chat.isAvailable());
  }

  public record Availability(boolean available) {}

  /** Answers one question about the view the caller is looking at. */
  @PostMapping
  public ChatService.Answer ask(
      @RequestParam(required = false) String bu,
      @ModelAttribute FilterQuery filters,
      @RequestBody AskRequest request) {

    if (request == null) {
      throw new IllegalArgumentException("A question is required.");
    }
    var scoped = guard.resolve(bu, "ASSISTANT", "ASK_ASSISTANT");
    List<Turn> history =
        request.history() == null
            ? List.of()
            : request.history().stream()
                .filter(m -> m != null)
                .map(m -> new Turn(m.role(), m.text()))
                .toList();

    return chat.answer(
        loader.load(scoped, filters.toSpec()), scoped.scope(), request.message(), history);
  }
}
