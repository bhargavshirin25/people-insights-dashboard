import "./globals.css";
import { SessionProvider } from "@/lib/session";
import { THEME_BOOTSTRAP } from "@/lib/theme";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Robin Insights — HR Operations",
  description: "BU-level people metrics for HR business partners at LeadSquared",
  robots: { index: false, follow: false },
};

/**
 * `suppressHydrationWarning` covers exactly one attribute: the theme script below sets `data-theme` on
 * this element before React hydrates, so the server's markup and the browser's disagree by design.
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        {/* Applies a stored theme choice ahead of the first paint, so dark does not begin as white. */}
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOTSTRAP }} />
      </head>
      <body>
        <SessionProvider>{children}</SessionProvider>
      </body>
    </html>
  );
}
