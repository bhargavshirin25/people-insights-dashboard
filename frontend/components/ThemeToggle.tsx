"use client";

import { THEME_KEY } from "@/lib/theme";
import { useEffect, useState } from "react";

type Theme = "light" | "dark";

function systemTheme(): Theme {
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

/**
 * Light and dark, from the header.
 *
 * The stylesheet has three states, not two: `data-theme` set to either value pins the theme, and no
 * attribute means follow the operating system. This keeps that shape — nothing is written until the
 * reader actually picks a side, so an untouched dashboard still turns dark at dusk with the rest of
 * their machine. Once they pick, the choice wins in both directions and survives a reload.
 *
 * The icon shows the theme the button switches *to*, which is what makes a single-button switch
 * readable: the moon means "go dark", not "you are dark".
 */
export function ThemeToggle() {
  // Null until mounted. The server cannot know the reader's system preference, so rendering an icon
  // before that is known would mean rendering the wrong one and then swapping it.
  const [theme, setTheme] = useState<Theme | null>(null);

  useEffect(() => {
    let stored: string | null = null;
    try {
      stored = window.localStorage.getItem(THEME_KEY);
    } catch {
      // Blocked storage: the toggle still works for this page view, it just will not be remembered.
    }
    setTheme(stored === "light" || stored === "dark" ? stored : systemTheme());
  }, []);

  // While no choice is stored the stylesheet is already following the system on its own; this only
  // keeps the icon honest when the machine changes theme under us.
  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      let stored: string | null = null;
      try {
        stored = window.localStorage.getItem(THEME_KEY);
      } catch {
        stored = null;
      }
      if (stored !== "light" && stored !== "dark") {
        setTheme(media.matches ? "dark" : "light");
      }
    };
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, []);

  const next: Theme = theme === "dark" ? "light" : "dark";

  const toggle = () => {
    setTheme(next);
    document.documentElement.setAttribute("data-theme", next);
    try {
      window.localStorage.setItem(THEME_KEY, next);
    } catch {
      // Nothing to do: the attribute is set, so this page view is already in the chosen theme.
    }
  };

  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={`Switch to the ${next} theme`}
      title={`Switch to the ${next} theme`}
      className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-md transition-colors hover:bg-[var(--surface-2)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-ring)] md:h-8 md:w-8"
      style={{ border: "1px solid var(--border-strong)", color: "var(--text-secondary)" }}
    >
      {/* Nothing until the current theme is known, so the icon never renders wrong and then flips. */}
      {theme === null ? (
        <span className="sr-only">Loading the theme</span>
      ) : next === "dark" ? (
        <Moon />
      ) : (
        <Sun />
      )}
    </button>
  );
}

function Moon() {
  return (
    <svg
      viewBox="0 0 20 20"
      width="17"
      height="17"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M16.5 12.4A7 7 0 0 1 7.6 3.5a7 7 0 1 0 8.9 8.9Z" />
    </svg>
  );
}

function Sun() {
  return (
    <svg
      viewBox="0 0 20 20"
      width="17"
      height="17"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="10" cy="10" r="3.6" />
      <path d="M10 1.8v2M10 16.2v2M1.8 10h2M16.2 10h2M4.2 4.2l1.4 1.4M14.4 14.4l1.4 1.4M15.8 4.2l-1.4 1.4M5.6 14.4l-1.4 1.4" />
    </svg>
  );
}
