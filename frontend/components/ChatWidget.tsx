"use client";

import { api, ApiError } from "@/lib/api";
import { useFilters } from "@/lib/filters";
import { formatDate, PERIOD_LABELS } from "@/lib/format";
import { useSession } from "@/lib/session";
import type { ChatAnswer, ChatAvailability, ChatTurn } from "@/lib/types";
import { usePathname } from "next/navigation";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

/**
 * Fifty exchanges, which is what the backend carries as context.
 *
 * Keeping the browser's copy at the same size as the context window means what the reader can scroll
 * back to and what the assistant can remember are the same conversation. A larger local history would
 * show the reader messages the next answer cannot see.
 */
const MAX_STORED = 100;

const STORAGE_PREFIX = "people-insights.chat.";

/** Whether the panel has ever been opened in this browser. */
const OPENED_KEY = "people-insights.chat.opened";

/** Openers per view, so the first question is one click rather than a blank box. */
const SUGGESTIONS: Record<string, string[]> = {
  "/": ["What moved most this month?", "What should I look at first?"],
  "/risk": ["Who is at highest risk, and why?", "Which factors drive most of the risk?"],
  "/exit": ["Why are people leaving?", "Which exit theme is worst right now?"],
  "/performance": [
    "Where do performance and engagement disagree?",
    "Any high performers never promoted?",
  ],
  "/leave-attendance": ["Which teams have an attendance problem?", "Who is showing burnout signals?"],
  "/heatmap": ["Which business unit needs attention first?", "Compare attrition across the BUs"],
  "/audit": ["What does this view log?", "Summarise the figures in view"],
};

const FALLBACK_SUGGESTIONS = ["Summarise this view", "What needs attention?"];

type Kind = "user" | "assistant" | "error";

interface Entry {
  id: string;
  kind: Kind;
  text: string;
  /** A caveat shown under an assistant reply — a truncated answer, or a declined one. */
  note?: string;
}

let sequence = 0;

function nextId(): string {
  sequence += 1;
  return `m${Date.now().toString(36)}${sequence}`;
}

/**
 * The dashboard assistant, in a panel at the bottom right of every view.
 *
 * <p>Every question goes to the API with the current business unit and filters attached, so the
 * assistant answers about the view in front of the reader rather than about the dataset in general.
 * The transcript lives in this browser, keyed by the signed-in address: a conversation that may name
 * individuals is not something to leave in a shared table with its own retention rule, and the server
 * needs no memory of it because the fifty most recent exchanges travel with each question.
 */
export function ChatWidget() {
  const { session, can } = useSession();
  const { filters, options, query, activeCount } = useFilters();
  const pathname = usePathname();

  const [available, setAvailable] = useState<boolean | null>(null);
  const [open, setOpen] = useState(false);
  const [entries, setEntries] = useState<Entry[]>([]);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState(false);
  const [retryable, setRetryable] = useState<string | null>(null);
  const [restored, setRestored] = useState(false);

  const scroller = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);

  const storageKey = session?.email ? `${STORAGE_PREFIX}${session.email}` : null;

  // The panel is only offered when the instance has an API key, because a blank one is a supported
  // configuration and an input that can only fail is worse than no input.
  useEffect(() => {
    if (!session?.authenticated || !can("USE_ASSISTANT")) {
      return;
    }
    api
      .get<ChatAvailability>("/api/chat/availability")
      .then((state) => setAvailable(state.available))
      .catch(() => setAvailable(false));
  }, [session?.authenticated, can]);

  // Restore the conversation, then open the panel on the reader's first visit so the assistant is
  // found rather than discovered. After that its state is whatever they last left it as.
  useEffect(() => {
    if (!storageKey || restored) {
      return;
    }
    try {
      const stored = window.localStorage.getItem(storageKey);
      if (stored) {
        const parsed: Entry[] = JSON.parse(stored);
        if (Array.isArray(parsed)) {
          setEntries(parsed.filter((e) => e && (e.kind === "user" || e.kind === "assistant")));
        }
      }
    } catch {
      // A corrupt or unreadable transcript starts an empty conversation rather than breaking the view.
    }
    try {
      if (!window.localStorage.getItem(OPENED_KEY)) {
        setOpen(true);
        window.localStorage.setItem(OPENED_KEY, "1");
      }
    } catch {
      // Blocked storage: the panel stays closed and the launcher is still there.
    }
    setRestored(true);
  }, [storageKey, restored]);

  // Persist the transcript, dropping the oldest beyond what the context window carries anyway.
  useEffect(() => {
    if (!storageKey || !restored) {
      return;
    }
    try {
      const keep = entries.filter((e) => e.kind !== "error").slice(-MAX_STORED);
      window.localStorage.setItem(storageKey, JSON.stringify(keep));
    } catch {
      // A full quota must not break the conversation in progress.
    }
  }, [entries, storageKey, restored]);

  const scrollToEnd = useCallback(() => {
    const node = scroller.current;
    if (node) {
      node.scrollTop = node.scrollHeight;
    }
  }, []);

  useEffect(() => {
    if (open) {
      scrollToEnd();
    }
  }, [open, entries, pending, scrollToEnd]);

  useEffect(() => {
    if (open) {
      input.current?.focus();
    }
  }, [open]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  const ask = useCallback(
    async (question: string) => {
      const text = question.trim();
      if (!text || pending) {
        return;
      }
      // The history is what the reader can see above, so an answer is never written from turns that
      // scrolled out of the panel.
      const history: ChatTurn[] = entries
        .filter((e) => e.kind !== "error")
        .slice(-MAX_STORED)
        .map((e) => ({ role: e.kind as "user" | "assistant", text: e.text }));

      setEntries((prev) => [...prev, { id: nextId(), kind: "user", text }]);
      setDraft("");
      // The box grew with the question; clearing the value does not shrink an inline height back.
      if (input.current) {
        input.current.style.height = "auto";
      }
      setRetryable(null);
      setPending(true);
      try {
        const answer = await api.post<ChatAnswer>(`/api/chat${query()}`, { message: text, history });
        setEntries((prev) => [
          ...prev,
          {
            id: nextId(),
            kind: "assistant",
            text: answer.text,
            note: answer.truncated
              ? "This answer reached its length limit — ask for a shorter version or a specific figure."
              : undefined,
          },
        ]);
      } catch (e) {
        const message =
          e instanceof ApiError
            ? e.message
            : "The assistant could not be reached. Your question was not answered.";
        setEntries((prev) => [...prev, { id: nextId(), kind: "error", text: message }]);
        setRetryable(text);
      } finally {
        setPending(false);
      }
    },
    [entries, pending, query],
  );

  const clear = useCallback(() => {
    setEntries([]);
    setRetryable(null);
    if (storageKey) {
      try {
        window.localStorage.removeItem(storageKey);
      } catch {
        // Nothing to do: the in-memory conversation is already gone.
      }
    }
    input.current?.focus();
  }, [storageKey]);

  const scopeLabel = useMemo(() => {
    if (filters.bu) {
      return filters.bu;
    }
    const assigned = session?.assignedBus ?? [];
    return assigned.length === 1 ? assigned[0] : "All units in your scope";
  }, [filters.bu, session?.assignedBus]);

  const suggestions = SUGGESTIONS[pathname] ?? FALLBACK_SUGGESTIONS;
  const conversation = entries.filter((e) => e.kind !== "error");

  // Without the assistant permission there is nothing here to answer from, and the API refuses anyway.
  if (!session?.authenticated || !can("USE_ASSISTANT") || available === false) {
    return null;
  }

  if (!open) {
    return (
      <Launcher
        onOpen={() => setOpen(true)}
        unread={conversation.length > 0}
        loading={available === null}
      />
    );
  }

  return (
    <div
      className="chat-panel no-print fixed z-40 flex flex-col overflow-hidden rounded-xl inset-x-2 bottom-2 top-14 sm:inset-x-auto sm:top-auto sm:right-4 sm:bottom-4 sm:h-[min(38rem,calc(100dvh-7rem))] sm:w-[23.5rem]"
      style={{
        background: "var(--surface-1)",
        border: "1px solid var(--border-strong)",
        boxShadow: "0 18px 48px rgba(0, 0, 0, 0.26)",
      }}
      role="dialog"
      aria-label="Ask Robin — dashboard assistant"
    >
      <header
        className="hairline-b flex shrink-0 items-center gap-2 px-3 py-2.5"
        style={{ background: "var(--surface-2)" }}
      >
        <img
          src="/robin.png"
          alt=""
          width={26}
          height={26}
          className="h-[26px] w-[26px] shrink-0 rounded object-contain"
        />
        <span className="min-w-0 flex-1 leading-[1.2]">
          <span className="flex items-center gap-1.5 text-[12.5px] font-semibold tracking-tight">
            Ask Robin
            <Sparkle busy={pending} />
          </span>
          <span className="block truncate text-[10px]" style={{ color: "var(--text-muted)" }}>
            {scopeLabel} · {PERIOD_LABELS[filters.period] ?? filters.period}
            {activeCount > 0 && ` · ${activeCount} filter${activeCount === 1 ? "" : "s"}`}
            {options?.asOf && ` · as of ${formatDate(options.asOf)}`}
          </span>
        </span>
        {conversation.length > 0 && (
          <IconButton label="Clear this conversation" onClick={clear} destructive>
            <path d="M3 5h10M6.5 5V3.5h3V5M5 5l.6 8h4.8L11 5" />
          </IconButton>
        )}
        <IconButton label="Close the assistant" onClick={() => setOpen(false)}>
          <path d="M4 4l8 8M12 4l-8 8" />
        </IconButton>
      </header>

      <div ref={scroller} className="chat-scroll min-h-0 flex-1 space-y-2.5 overflow-y-auto px-3 py-3">
        {conversation.length === 0 && (
          <div className="space-y-2.5">
            <p className="text-[11.5px] leading-relaxed" style={{ color: "var(--text-secondary)" }}>
              I answer from the figures on this dashboard, for the business unit and filters you have
              in view. I cannot see anything outside your access, and every question is logged with
              your identity like any other data access here.
            </p>
            <div className="flex flex-wrap gap-1.5">
              {suggestions.map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => void ask(s)}
                  className="rounded-full border px-2.5 py-1 text-left text-[11px] transition-colors hover:opacity-80"
                  style={{
                    borderColor: "var(--border-strong)",
                    background: "var(--surface-2)",
                    color: "var(--text-secondary)",
                  }}
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}

        {entries.map((entry) => (
          <Message key={entry.id} entry={entry} onRetry={retryable ? () => void ask(retryable) : undefined} />
        ))}

        {pending && (
          <div
            className="inline-flex items-center gap-2 rounded-lg px-2.5 py-2 text-[11.5px]"
            style={{ background: "var(--surface-2)", color: "var(--text-secondary)" }}
            aria-live="polite"
          >
            <span aria-hidden="true" className="flex gap-1">
              <Dot delay={0} />
              <Dot delay={0.15} />
              <Dot delay={0.3} />
            </span>
            Reading the figures on this view…
          </div>
        )}
      </div>

      <form
        className="shrink-0 px-3 pt-2 pb-3"
        style={{ borderTop: "1px solid var(--border-hairline)" }}
        onSubmit={(e) => {
          e.preventDefault();
          void ask(draft);
        }}
      >
        <div
          className="flex items-end gap-1.5 rounded-lg px-2 py-1.5"
          style={{ background: "var(--surface-2)", border: "1px solid var(--border-strong)" }}
        >
          <textarea
            ref={input}
            rows={1}
            value={draft}
            placeholder="Ask about these figures…"
            onChange={(e) => {
              setDraft(e.target.value);
              // Grow with the text, to a few lines, then scroll inside itself.
              e.target.style.height = "auto";
              e.target.style.height = `${Math.min(e.target.scrollHeight, 108)}px`;
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                void ask(draft);
              }
            }}
            className="max-h-[108px] min-h-[22px] w-full resize-none bg-transparent text-[16px] leading-snug outline-none sm:text-[12.5px]"
            style={{ color: "var(--text-primary)" }}
            aria-label="Your question"
          />
          <button
            type="submit"
            disabled={pending || draft.trim().length === 0}
            aria-label="Send"
            className="mb-[1px] inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-white transition-opacity disabled:opacity-40"
            style={{ background: "var(--series-1)" }}
          >
            <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
              <path d="M8 13V3M8 3l-4 4M8 3l4 4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
        <p className="mt-1.5 text-[9.5px] leading-tight" style={{ color: "var(--text-muted)" }}>
          Generated from this view&rsquo;s figures. Check anything you plan to act on against the cards
          themselves.
        </p>
      </form>
    </div>
  );
}

/**
 * The closed state, bottom right on every view.
 *
 * Two forms. From the small breakpoint up there is room for a named pill — the mark, "Ask Robin", and
 * the sparkle — because a labelled launcher is recognisable as a chat before it is clicked, which a
 * bare circle in the corner of a dense dashboard is not. On a phone the label would eat a third of the
 * width, so it collapses to the mark alone.
 *
 * The pill is the one deliberately dark surface in the application. It sits over page content rather
 * than in the layout, so it has to read as "floating above" in both themes: on the light page a card
 * surface would disappear into it, and on the dark page it is separated by its border and shadow.
 */
function Launcher({
  onOpen,
  unread,
  loading,
}: {
  onOpen: () => void;
  unread: boolean;
  loading: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onOpen}
      disabled={loading}
      aria-label="Ask Robin about this dashboard"
      className="chat-launcher no-print fixed right-4 bottom-4 z-40 inline-flex items-center gap-0 rounded-full p-[5px] transition-transform hover:-translate-y-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-ring)] disabled:opacity-0 sm:gap-2 sm:pr-4"
      style={{
        background: "var(--chat-launcher-bg)",
        border: "1px solid var(--chat-launcher-border)",
        boxShadow: "0 10px 28px rgba(0, 0, 0, 0.3)",
      }}
    >
      <span className="relative inline-flex shrink-0">
        <img
          src="/robin.png"
          alt=""
          width={38}
          height={38}
          className="h-[38px] w-[38px] rounded-full object-contain"
          style={{ background: "#ffffff" }}
        />
        {unread && (
          <span
            aria-hidden="true"
            className="absolute -right-0.5 -bottom-0.5 h-[11px] w-[11px] rounded-full"
            style={{ background: "var(--status-good)", border: "2px solid var(--chat-launcher-bg)" }}
            title="This conversation has messages in it"
          />
        )}
      </span>

      <span
        className="hidden items-center gap-1.5 text-[13px] font-semibold whitespace-nowrap sm:inline-flex"
        style={{ color: "var(--chat-launcher-fg)" }}
      >
        Ask Robin
        <svg
          aria-hidden="true"
          viewBox="0 0 16 16"
          width="12"
          height="12"
          fill="currentColor"
          className="ai-sparkle"
          style={{ opacity: 0.9 }}
        >
          <path
            className="ai-sparkle-lg"
            d="M6.5 1 7.68 5.12 11.8 6.3 7.68 7.48 6.5 11.6 5.32 7.48 1.2 6.3 5.32 5.12Z"
          />
        </svg>
      </span>

      {/* The phone form has no label, so the sparkle moves to the corner to say what it is. */}
      <span
        aria-hidden="true"
        className="absolute -top-0.5 -right-0.5 flex h-[15px] w-[15px] items-center justify-center rounded-full sm:hidden"
        style={{ background: "var(--series-1)", color: "#fff", border: "1.5px solid var(--chat-launcher-bg)" }}
      >
        <svg viewBox="0 0 16 16" width="8" height="8" fill="currentColor">
          <path d="M6.5 1 7.68 5.12 11.8 6.3 7.68 7.48 6.5 11.6 5.32 7.48 1.2 6.3 5.32 5.12Z" />
        </svg>
      </span>
    </button>
  );
}

function Message({ entry, onRetry }: { entry: Entry; onRetry?: () => void }) {
  if (entry.kind === "user") {
    return (
      <div className="flex justify-end">
        <div
          className="max-w-[85%] rounded-lg rounded-br-sm px-2.5 py-1.5 text-[12px] leading-relaxed whitespace-pre-wrap"
          style={{
            background: "color-mix(in srgb, var(--series-1) 14%, var(--surface-1))",
            border: "1px solid color-mix(in srgb, var(--series-1) 30%, transparent)",
          }}
        >
          {entry.text}
        </div>
      </div>
    );
  }

  if (entry.kind === "error") {
    return (
      <div
        className="rounded-lg px-2.5 py-2 text-[11.5px] leading-relaxed"
        style={{
          background: "color-mix(in srgb, var(--status-critical) 10%, var(--surface-1))",
          borderLeft: "3px solid var(--status-critical)",
          color: "var(--text-secondary)",
        }}
        role="alert"
      >
        {entry.text}
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="mt-1 block text-[11px] font-medium underline underline-offset-2"
            style={{ color: "var(--series-1)" }}
          >
            Ask it again
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="ai-rise flex justify-start">
      <div
        className="max-w-[92%] rounded-lg rounded-bl-sm px-2.5 py-2 text-[12px] leading-relaxed"
        style={{ background: "var(--surface-2)" }}
      >
        <RichText text={entry.text} />
        {entry.note && (
          <p className="mt-1.5 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
            {entry.note}
          </p>
        )}
      </div>
    </div>
  );
}

/**
 * The assistant's reply, rendered.
 *
 * It is told to answer in prose with "- " bullets and bold for a figure worth pulling out, so this
 * handles exactly that much: paragraphs, bullet lists, bold and inline code. Anything else arrives as
 * the plain text it already is, which is the right failure — a stray asterisk is legible, a broken
 * markdown parser is not.
 */
function RichText({ text }: { text: string }) {
  const blocks = useMemo(() => {
    const out: { type: "p" | "ul"; lines: string[] }[] = [];
    for (const raw of text.split("\n")) {
      const line = raw.trimEnd();
      const bullet = /^\s*[-*•]\s+/.test(line);
      const last = out[out.length - 1];
      if (!line.trim()) {
        continue;
      }
      if (bullet) {
        const content = line.replace(/^\s*[-*•]\s+/, "");
        if (last?.type === "ul") {
          last.lines.push(content);
        } else {
          out.push({ type: "ul", lines: [content] });
        }
        continue;
      }
      if (last?.type === "p") {
        last.lines.push(line);
      } else {
        out.push({ type: "p", lines: [line] });
      }
    }
    return out;
  }, [text]);

  return (
    <>
      {blocks.map((block, i) =>
        block.type === "ul" ? (
          <ul key={i} className={`space-y-1 ${i > 0 ? "mt-1.5" : ""}`}>
            {block.lines.map((line, j) => (
              <li key={j} className="flex gap-1.5">
                <span aria-hidden="true" style={{ color: "var(--series-1)" }}>
                  •
                </span>
                <span className="min-w-0">{inline(line)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p key={i} className={i > 0 ? "mt-1.5" : undefined}>
            {inline(block.lines.join(" "))}
          </p>
        ),
      )}
    </>
  );
}

/** Bold and inline code inside one line. */
function inline(text: string): ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, i) => {
    if (part.startsWith("**") && part.endsWith("**") && part.length > 4) {
      return (
        <strong key={i} className="font-semibold tnum">
          {part.slice(2, -2)}
        </strong>
      );
    }
    if (part.startsWith("`") && part.endsWith("`") && part.length > 2) {
      return (
        <code
          key={i}
          className="rounded px-1 py-[1px] text-[11px]"
          style={{ background: "var(--surface-1)" }}
        >
          {part.slice(1, -1)}
        </code>
      );
    }
    return <span key={i}>{part}</span>;
  });
}

/** The panel's identity mark, quickened while an answer is being written. */
function Sparkle({ busy }: { busy: boolean }) {
  return (
    <span className="ai-title inline-flex" data-busy={busy ? "true" : undefined}>
      <svg
        aria-hidden="true"
        className="ai-sparkle"
        viewBox="0 0 16 16"
        width="12"
        height="12"
        fill="currentColor"
        style={{ color: "var(--series-1)" }}
      >
        <path
          className="ai-sparkle-lg"
          d="M6.5 1 7.68 5.12 11.8 6.3 7.68 7.48 6.5 11.6 5.32 7.48 1.2 6.3 5.32 5.12Z"
        />
        <path
          className="ai-sparkle-sm"
          d="M12.1 9.2 12.72 11.38 14.9 12 12.72 12.62 12.1 14.8 11.48 12.62 9.3 12 11.48 11.38Z"
        />
      </svg>
    </span>
  );
}

function Dot({ delay }: { delay: number }) {
  return (
    <span
      className="chat-dot h-1.5 w-1.5 rounded-full"
      style={{ background: "var(--series-1)", animationDelay: `${delay}s` }}
    />
  );
}

/** A 28px square icon button, sized for a thumb on a phone as well as a pointer. */
function IconButton({
  label,
  onClick,
  children,
  destructive = false,
}: {
  label: string;
  onClick: () => void;
  children: ReactNode;
  /** Turns red on hover. For the one control here that throws something away. */
  destructive?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className={`inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md transition-colors hover:bg-[var(--surface-1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-ring)] ${
        destructive ? "hover:text-[var(--status-critical-text)]" : ""
      }`}
      style={{ color: "var(--text-secondary)" }}
    >
      <svg
        viewBox="0 0 16 16"
        width="14"
        height="14"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        {children}
      </svg>
    </button>
  );
}
