/** @type {import('next').NextConfig} */
const backend = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

const nextConfig = {
  reactStrictMode: true,

  /**
   * Proxy the API through Next rather than calling the backend cross-origin.
   *
   * This keeps the session cookie same-origin, which matters for more than convenience: the
   * cookie stays SameSite=Lax and HttpOnly with no CORS relaxation anywhere, and the CSRF
   * double-submit cookie works as designed. Nothing about the access model depends on the
   * browser honouring a cross-site cookie policy.
   */
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${backend}/api/:path*` },
      // The Entra SSO handshake happens on the backend origin.
      { source: "/oauth2/:path*", destination: `${backend}/oauth2/:path*` },
      { source: "/login/oauth2/:path*", destination: `${backend}/login/oauth2/:path*` },
    ];
  },

  async headers() {
    return [
      {
        // Employee data must not sit in any shared or intermediary cache.
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "same-origin" },
          { key: "X-Frame-Options", value: "DENY" },
        ],
      },
    ];
  },
};

export default nextConfig;
