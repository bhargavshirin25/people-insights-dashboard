"use client";

import { Notice, Skeleton } from "@/components/ui";
import { ApiError } from "@/lib/api";
import type { ReactNode } from "react";

/**
 * Renders the loading and error states every view shares.
 *
 * An access denial is presented as a distinct, explicit state rather than an empty view. That is the
 * point of the backend answering 403 instead of returning no rows: the person sees that the data
 * exists and they are not permitted it, and the attempt is in the audit trail either way.
 */
export function ViewState({
  loading,
  error,
  children,
}: {
  loading: boolean;
  error: Error | ApiError | null;
  children: ReactNode;
}) {
  if (error) {
    if (error instanceof ApiError && error.isAccessDenied) {
      return (
        <Notice title="Access denied" tone="critical">
          <p>{error.message}</p>
          {error.requestedBusinessUnit && error.requestedBusinessUnit !== "null" && (
            <p className="mt-1">
              Requested business unit: <strong>{error.requestedBusinessUnit}</strong>. This attempt has
              been recorded in the audit trail.
            </p>
          )}
        </Notice>
      );
    }
    if (error instanceof ApiError && error.isUnauthenticated) {
      return (
        <Notice title="Session expired" tone="warning">
          Your session has timed out. Sign in again to continue.
        </Notice>
      );
    }
    return (
      <Notice title="Could not load this view" tone="critical">
        {error.message}
      </Notice>
    );
  }

  if (loading) {
    return (
      <div className="card p-4">
        <Skeleton rows={5} />
      </div>
    );
  }

  return <>{children}</>;
}
