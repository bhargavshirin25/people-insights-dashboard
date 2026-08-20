/**
 * Client-side API access.
 *
 * Requests go to the Next origin and are proxied to Spring Boot, so the session cookie stays
 * same-origin. Write requests carry the CSRF double-submit header taken from the XSRF-TOKEN cookie
 * that Spring Security issues.
 */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestedBusinessUnit?: string;

  constructor(status: number, code: string, message: string, requestedBusinessUnit?: string) {
    super(message);
    this.status = status;
    this.code = code;
    this.requestedBusinessUnit = requestedBusinessUnit;
  }

  /** True when the failure is an access decision rather than a fault. */
  get isAccessDenied() {
    return this.status === 403;
  }

  get isUnauthenticated() {
    return this.status === 401;
  }
}

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const method = (init.method ?? "GET").toUpperCase();

  if (method !== "GET" && method !== "HEAD") {
    const token = csrfToken();
    if (token) {
      headers.set("X-XSRF-TOKEN", token);
    }
    if (init.body && !headers.has("content-type")) {
      headers.set("content-type", "application/json");
    }
  }

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  });

  if (!response.ok) {
    let code = "error";
    let message = `Request failed with status ${response.status}`;
    let bu: string | undefined;
    try {
      const body = await response.json();
      code = body.error ?? code;
      message = body.message ?? message;
      bu = body.requestedBusinessUnit;
    } catch {
      // A non-JSON error body: keep the generic message.
    }
    throw new ApiError(response.status, code, message, bu);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** Builds a query string from the filter state, omitting anything unset. */
export function filterQuery(params: {
  bu?: string | null;
  grades?: string[];
  locations?: string[];
  tenureMin?: number | null;
  tenureMax?: number | null;
  period?: string;
  from?: string | null;
  to?: string | null;
  extra?: Record<string, string | undefined>;
}): string {
  const q = new URLSearchParams();
  if (params.bu) q.set("bu", params.bu);
  params.grades?.forEach((g) => q.append("grade", g));
  params.locations?.forEach((l) => q.append("location", l));
  if (params.tenureMin != null) q.set("tenureMin", String(params.tenureMin));
  if (params.tenureMax != null) q.set("tenureMax", String(params.tenureMax));
  if (params.period) q.set("period", params.period);
  if (params.from) q.set("from", params.from);
  if (params.to) q.set("to", params.to);
  for (const [k, v] of Object.entries(params.extra ?? {})) {
    if (v) q.set(k, v);
  }
  const s = q.toString();
  return s ? `?${s}` : "";
}

export const api = {
  get: <T,>(path: string) => request<T>(path),

  /**
   * Multipart upload.
   *
   * The content type is deliberately not set: the browser has to write it itself so the multipart
   * boundary matches the body it generated. Everything else — the CSRF header, the same-origin cookie,
   * the error shape — is the same as any other write.
   */
  upload: <T,>(path: string, body: FormData) => request<T>(path, { method: "POST", body }),
  post: <T,>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  del: <T,>(path: string) => request<T>(path, { method: "DELETE" }),

  /** Downloads a PDF through the same session, then hands it to the browser. */
  async download(path: string, fallbackName: string) {
    const response = await fetch(path, { credentials: "same-origin", cache: "no-store" });
    if (!response.ok) {
      let message = `Export failed with status ${response.status}`;
      try {
        const body = await response.json();
        message = body.message ?? message;
      } catch {
        /* keep the generic message */
      }
      throw new ApiError(response.status, "export_failed", message);
    }
    const disposition = response.headers.get("content-disposition") ?? "";
    const match = disposition.match(/filename="?([^"]+)"?/);
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = match ? match[1] : fallbackName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  },
};
