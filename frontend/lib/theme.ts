/**
 * Theme storage, shared by the root layout and the header switch.
 *
 * Deliberately a plain module rather than part of the client component: the root layout is a server
 * component and has to embed the bootstrap below in the document head, and a value imported from a
 * `"use client"` module into a server component is a client reference rather than the string itself.
 */

export const THEME_KEY = "people-insights.theme";

/**
 * Runs before the page paints, from the document head, so a reader who chose dark never sees a white
 * flash first. It is a string rather than a module because it has to execute ahead of the bundle: by
 * the time React hydrates, the first paint has already happened.
 *
 * Absence of the attribute is a meaningful state — the stylesheet then follows the operating system
 * through `prefers-color-scheme` — so this writes nothing at all until a choice has been stored.
 */
export const THEME_BOOTSTRAP = `try{var t=localStorage.getItem("${THEME_KEY}");if(t==="light"||t==="dark"){document.documentElement.setAttribute("data-theme",t)}}catch(e){}`;
