"use client";

import { ChatWidget } from "@/components/ChatWidget";
import { FilterBar } from "@/components/FilterBar";
import { ThemeToggle } from "@/components/ThemeToggle";
import { Badge, Button } from "@/components/ui";
import { FilterProvider } from "@/lib/filters";
import { useSession } from "@/lib/session";
import type { PermissionKey } from "@/lib/types";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

interface NavItem {
  href: string;
  label: string;
  /**
   * The permission that opens this view. The API enforces the same one independently, so a link is
   * hidden as a convenience and never as the control.
   */
  permission: PermissionKey;
  /**
   * Whether the shared filter row moves anything on this view.
   *
   * A property of the route rather than of the reader: the audit trail, the access page and the data-source
   * page are about the application rather than about a selection of employees, so a row of employee filters
   * above them is furniture that does nothing. The endpoints behind them take no filter parameters either.
   */
  filtered?: boolean;
}

const NAV: NavItem[] = [
  { href: "/", label: "Overview", permission: "VIEW_OVERVIEW", filtered: true },
  { href: "/risk", label: "Attrition risk", permission: "VIEW_RISK", filtered: true },
  { href: "/exit", label: "Exit analysis", permission: "VIEW_EXIT", filtered: true },
  { href: "/performance", label: "Performance & engagement", permission: "VIEW_PERFORMANCE", filtered: true },
  { href: "/leave-attendance", label: "Leave & attendance", permission: "VIEW_LEAVE_ATTENDANCE", filtered: true },
  { href: "/heatmap", label: "Org heat map", permission: "VIEW_HEATMAP", filtered: true },
  { href: "/audit", label: "Audit trail", permission: "VIEW_AUDIT" },
  { href: "/access", label: "Access", permission: "MANAGE_ACCESS" },
  { href: "/data-source", label: "Data Source", permission: "MANAGE_CONFIG" },
];

/**
 * The signed-in shell: identity, navigation, the shared filter row, and the confidentiality label.
 *
 * Nav links are filtered by role for usability. That is not the access control — every endpoint
 * behind these links refuses an unauthorised caller on its own, so removing a link is cosmetic.
 *
 * The header has two forms rather than one that wraps. Eight links, an identity block and a sign-out
 * button do not fit a phone at any wrapping, and a header that grows to four rows takes the whole
 * screen before a single figure is visible — so below the medium breakpoint the links and the identity
 * move into a menu and the bar keeps one row.
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { session, loading, signOut, can } = useSession();
  const pathname = usePathname();
  const router = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (!loading && !session?.authenticated) {
      router.replace("/login");
    }
  }, [loading, session, router]);

  // A menu left open across a navigation would cover the view the reader just chose.
  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!menuOpen) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setMenuOpen(false);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [menuOpen]);

  if (loading || !session?.authenticated) {
    return (
      <main className="flex min-h-screen items-center justify-center">
        <p className="text-[12.5px]" style={{ color: "var(--text-muted)" }}>
          {loading ? "Verifying your session…" : "Redirecting to sign-in…"}
        </p>
      </main>
    );
  }

  const visible = NAV.filter((item) => can(item.permission));
  // Shown on the views the row actually filters, and only to a reader allowed to open them.
  const filteredView = visible.some((item) => item.href === pathname && item.filtered);

  // Name over role name. The business-unit list used to sit here too and ran to eight items on an
  // org-wide role, which pushed the header into a second line to say something the filter row already
  // shows — so the role's name is all that is left.
  const identity = (
    <>
      <span className="block font-medium">{session.displayName ?? session.email}</span>
      <span style={{ color: "var(--text-muted)" }}>
        {session.customRoles.length > 0 ? session.customRoles.join(", ") : "No role assigned"}
      </span>
    </>
  );

  return (
    <FilterProvider>
      <div className="min-h-screen">
        {/* Above the assistant panel's layer, so opening the phone menu while the panel is open shows
            the menu rather than hiding it behind a full-width sheet. */}
        <header
          className="sticky top-0 z-50 no-print"
          // The same drawn edge the cards have, so the bar has a base rather than fading into the page.
          style={{ background: "var(--surface-1)", borderBottom: "1px solid var(--card-border)" }}
        >
          <div className="mx-auto flex max-w-[1600px] flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5 sm:px-5">
            <Link href="/" className="flex items-center gap-2">
              {/* public/robin.png — a plain img rather than next/image, so the header renders
                  unchanged whether or not the asset is present. */}
              <img
                src="/robin.png"
                alt=""
                width={30}
                height={30}
                className="h-[30px] w-[30px] shrink-0 rounded object-contain"
              />
              <span className="flex flex-col leading-[1.15]">
                <span className="text-[13.5px] font-semibold tracking-tight">Robin Insights</span>
                <span className="text-[9.5px]" style={{ color: "var(--text-muted)" }}>
                  Powered By LeadSquared
                </span>
              </span>
            </Link>

            <nav
              className="order-3 hidden w-full flex-wrap gap-x-1 gap-y-1 md:order-none md:flex md:w-auto"
              aria-label="Views"
            >
              {visible.map((item) => {
                const active = pathname === item.href;
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    className="rounded-md px-2.5 py-1 text-[11.5px] transition-colors"
                    style={{
                      background: active ? "var(--accent-wash)" : "transparent",
                      color: active ? "var(--accent-ink)" : "var(--text-secondary)",
                      fontWeight: active ? 600 : 400,
                    }}
                  >
                    {item.label}
                  </Link>
                );
              })}
            </nav>

            {/* The theme switch stays out of the menu and out of the desktop-only cluster: it belongs at
                the top right at every width, next to the menu button on a phone. */}
            <div className="ml-auto flex items-center gap-2">
              <ThemeToggle />

              <div className="hidden items-center gap-2 md:flex">
                <span className="text-right text-[11px] leading-tight">{identity}</span>
                <Button variant="ghost" onClick={signOut}>
                  Sign out
                </Button>
              </div>

              {/* The phone form: one control, 40px square, in place of the links and the identity. */}
              <button
                type="button"
                onClick={() => setMenuOpen((v) => !v)}
                aria-expanded={menuOpen}
                aria-controls="app-menu"
                aria-label={menuOpen ? "Close the menu" : "Open the menu"}
                className="inline-flex h-10 w-10 items-center justify-center rounded-md transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-ring)] md:hidden"
                style={{ border: "1px solid var(--border-strong)", color: "var(--text-secondary)" }}
              >
                <svg
                  viewBox="0 0 20 20"
                  width="18"
                  height="18"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinecap="round"
                  aria-hidden="true"
                >
                  {menuOpen ? (
                    <path d="M5 5l10 10M15 5L5 15" />
                  ) : (
                    <path d="M3 6h14M3 10h14M3 14h14" />
                  )}
                </svg>
              </button>
            </div>
          </div>

          {menuOpen && (
            <div
              id="app-menu"
              className="md:hidden"
              style={{ borderTop: "1px solid var(--border-hairline)" }}
            >
              <nav className="mx-auto max-w-[1600px] px-3 py-2" aria-label="Views">
                <ul className="grid gap-0.5">
                  {visible.map((item) => {
                    const active = pathname === item.href;
                    return (
                      <li key={item.href}>
                        <Link
                          href={item.href}
                          aria-current={active ? "page" : undefined}
                          className="block rounded-md px-3 py-2.5 text-[13px] transition-colors"
                          style={{
                            background: active ? "var(--accent-wash)" : "transparent",
                            color: active ? "var(--accent-ink)" : "var(--text-secondary)",
                            fontWeight: active ? 600 : 400,
                          }}
                        >
                          {item.label}
                        </Link>
                      </li>
                    );
                  })}
                </ul>
                <div
                  className="mt-2 flex items-center justify-between gap-3 px-3 pt-2.5"
                  style={{ borderTop: "1px solid var(--border-hairline)" }}
                >
                  <span className="min-w-0 text-[11px] leading-tight">{identity}</span>
                  <Button variant="secondary" onClick={signOut}>
                    Sign out
                  </Button>
                </div>
              </nav>
            </div>
          )}
        </header>

        <div className="mx-auto max-w-[1600px] space-y-4 px-4 py-4 sm:px-5">
          {visible.length === 0 && (
            <div
              className="card p-4"
              style={{ borderLeftWidth: 3, borderLeftColor: "var(--status-warning)" }}
            >
              <h2 className="text-[12.5px] font-semibold">No access has been granted to this account</h2>
              <p className="mt-1 text-[11.5px] leading-relaxed" style={{ color: "var(--text-secondary)" }}>
                Access on this dashboard comes from a role assigned to your email address. Nobody has
                assigned one to {session.email} yet, so there is nothing to show. Ask whoever administers
                access to add you to a role.
              </p>
            </div>
          )}
          {filteredView && <FilterBar />}
          {/* The aggregation notice belongs with the employee data it describes, not above a config screen. */}
          {!session.canSeeIndividualPii && filteredView && (
            <div
              className="card px-3 py-2 text-[11px]"
              style={{ borderLeftWidth: 3, borderLeftColor: "var(--status-warning)", color: "var(--text-secondary)" }}
            >
              <Badge tone="warning" glyph="◆">
                Aggregated view
              </Badge>{" "}
              Your role sees anonymised, aggregated metrics only. Employee names, identifiers and
              individual-level data are withheld.
            </div>
          )}
          {children}
        </div>

        {/* Bottom padding on the phone so the last line of a view clears the assistant's launcher. */}
        <footer
          className="mx-auto max-w-[1600px] px-4 pt-2 pb-24 text-[10px] sm:px-5 sm:pb-6"
          style={{ color: "var(--text-muted)" }}
        >
          Confidential — HR Operations — LeadSquared. Every data access on this dashboard is logged with
          your identity, the business unit and the data type, and retained for 90 days.
        </footer>

        <ChatWidget />
      </div>
    </FilterProvider>
  );
}
