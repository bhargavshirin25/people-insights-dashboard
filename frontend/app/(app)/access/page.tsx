"use client";

import { Badge, Button, DataTable, Notice, Panel, Skeleton, Td } from "@/components/ui";
import { api, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSession } from "@/lib/session";
import type {
  AccessCatalog,
  CustomRoleView,
  PermissionKey,
  PersonAccess,
} from "@/lib/types";
import { useCallback, useEffect, useMemo, useState } from "react";

interface Draft {
  id: string | null;
  name: string;
  description: string;
  permissions: PermissionKey[];
  businessUnits: string[];
  allBusinessUnits: boolean;
}

const EMPTY_DRAFT: Draft = {
  id: null,
  name: "",
  description: "",
  permissions: [],
  businessUnits: [],
  allBusinessUnits: false,
};

/**
 * Access administration: custom roles, what each one may reach, and whose addresses hold it.
 *
 * The checkbox catalogue is served by the API rather than listed here, so a permission added to the
 * enforcement layer appears on the screen that grants it instead of being enforceable but ungrantable.
 * Nothing on this page is the access control — every permission it writes is resolved server-side on
 * each request, and a role that hides a link here still returns 403 to anyone who types the URL.
 */
export default function AccessPage() {
  const { session, can } = useSession();
  const [catalog, setCatalog] = useState<AccessCatalog | null>(null);
  const [roles, setRoles] = useState<CustomRoleView[] | null>(null);
  const [people, setPeople] = useState<PersonAccess[] | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [memberDraft, setMemberDraft] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    try {
      const [cat, roleList, peopleList] = await Promise.all([
        api.get<AccessCatalog>("/api/access/catalog"),
        api.get<CustomRoleView[]>("/api/access/roles"),
        api.get<PersonAccess[]>("/api/access/people"),
      ]);
      setCatalog(cat);
      setRoles(roleList);
      setPeople(peopleList);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not load the access configuration.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const labels = useMemo(() => {
    const map = new Map<string, string>();
    catalog?.permissions.forEach((p) => map.set(p.key, p.label));
    return map;
  }, [catalog]);

  // The API refuses this too; the page says so rather than showing a form that cannot save.
  if (session && !can("MANAGE_ACCESS")) {
    return (
      <Notice title="You do not have access to this screen" tone="warning">
        Managing roles requires the &ldquo;Manage access&rdquo; permission. Ask someone who holds it —
        they can grant it on this same screen.
      </Notice>
    );
  }

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await api.post<CustomRoleView>("/api/access/roles", {
        id: draft.id,
        name: draft.name,
        description: draft.description,
        permissions: draft.permissions,
        businessUnits: draft.businessUnits,
        allBusinessUnits: draft.allBusinessUnits,
      });
      setDraft(EMPTY_DRAFT);
      setEditing(false);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not save the role.");
    } finally {
      setBusy(false);
    }
  }

  async function remove(role: CustomRoleView) {
    setBusy(true);
    setError(null);
    try {
      await api.del(`/api/access/roles/${role.id}`);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not delete the role.");
    } finally {
      setBusy(false);
    }
  }

  async function addMember(role: CustomRoleView) {
    const email = (memberDraft[role.id] ?? "").trim();
    if (!email) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/access/roles/${role.id}/members`, { email });
      setMemberDraft((d) => ({ ...d, [role.id]: "" }));
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not add that address.");
    } finally {
      setBusy(false);
    }
  }

  async function removeMember(role: CustomRoleView, email: string) {
    setBusy(true);
    setError(null);
    try {
      await api.del(`/api/access/roles/${role.id}/members?email=${encodeURIComponent(email)}`);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not remove that address.");
    } finally {
      setBusy(false);
    }
  }

  function startEdit(role: CustomRoleView) {
    setDraft({
      id: role.id,
      name: role.name,
      description: role.description ?? "",
      permissions: [...role.permissions],
      businessUnits: [...role.businessUnits],
      allBusinessUnits: role.allBusinessUnits,
    });
    setEditing(true);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function toggle(permission: PermissionKey) {
    setDraft((d) => ({
      ...d,
      permissions: d.permissions.includes(permission)
        ? d.permissions.filter((p) => p !== permission)
        : [...d.permissions, permission],
    }));
  }

  function toggleBu(bu: string) {
    setDraft((d) => ({
      ...d,
      businessUnits: d.businessUnits.includes(bu)
        ? d.businessUnits.filter((b) => b !== bu)
        : [...d.businessUnits, bu],
    }));
  }

  return (
    <div className="space-y-4">
      <Panel
        title="Access"
        subtitle="Roles are sets of permissions granted to email addresses. A role decides which screens open, how much of the underlying data they show, and which business units are in scope."
      >
        <p className="text-[11.5px] leading-relaxed" style={{ color: "var(--text-secondary)" }}>
          A role is the only source of access: an address no role names reaches nothing at all. Two roles
          on one address are combined — permissions and business units alike. Every change here is written
          to the audit trail under your name, because it changes what somebody else can read.
        </p>
      </Panel>

      {error && (
        <Notice title="That did not work" tone="critical">
          {error}
        </Notice>
      )}

      <Panel
        title={editing ? `Editing ${draft.name || "role"}` : "New role"}
        subtitle={
          editing
            ? "Saving replaces this role's permissions and scope for everyone who holds it."
            : "Name it after the job it does, then tick what that job needs."
        }
        actions={
          editing ? (
            <Button
              variant="ghost"
              onClick={() => {
                setDraft(EMPTY_DRAFT);
                setEditing(false);
              }}
            >
              Cancel
            </Button>
          ) : undefined
        }
      >
        {!catalog ? (
          <Skeleton rows={6} />
        ) : (
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block">
                <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                  Role name
                </span>
                <input
                  value={draft.name}
                  onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
                  placeholder="Regional HRBP — South"
                  className="mt-1 w-full rounded-md px-2.5 py-1.5 text-[12.5px]"
                  style={{
                    background: "var(--surface-2)",
                    border: "1px solid var(--border-strong)",
                    color: "var(--text-primary)",
                  }}
                />
              </label>
              <label className="block">
                <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                  What it is for <span style={{ color: "var(--text-muted)" }}>(optional)</span>
                </span>
                <input
                  value={draft.description}
                  onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))}
                  placeholder="Reads risk and exits for the southern units, no compensation"
                  className="mt-1 w-full rounded-md px-2.5 py-1.5 text-[12.5px]"
                  style={{
                    background: "var(--surface-2)",
                    border: "1px solid var(--border-strong)",
                    color: "var(--text-primary)",
                  }}
                />
              </label>
            </div>

            {catalog.groups.map((group) => {
              const inGroup = catalog.permissions.filter((p) => p.group === group.key);
              if (inGroup.length === 0) {
                return null;
              }
              return (
                <fieldset key={group.key}>
                  <legend className="text-[11.5px] font-semibold">{group.label}</legend>
                  <p className="mb-1.5 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                    {group.description}
                  </p>
                  <div className="grid gap-1.5 sm:grid-cols-2">
                    {inGroup.map((permission) => {
                      const checked = draft.permissions.includes(permission.key);
                      return (
                        <label
                          key={permission.key}
                          className="flex cursor-pointer gap-2 rounded-md p-2 transition-colors"
                          style={{
                            background: checked ? "var(--accent-wash)" : "var(--surface-2)",
                            border: `1px solid ${checked ? "var(--accent-ink)" : "var(--border-hairline)"}`,
                          }}
                        >
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={() => toggle(permission.key)}
                            className="mt-[2px] h-3.5 w-3.5 shrink-0 cursor-pointer accent-[var(--series-1)]"
                          />
                          <span className="min-w-0">
                            <span
                              className="block text-[12px] font-medium"
                              style={{ color: checked ? "var(--accent-ink)" : "var(--text-primary)" }}
                            >
                              {permission.label}
                            </span>
                            <span
                              className="block text-[10.5px] leading-snug"
                              style={{ color: "var(--text-muted)" }}
                            >
                              {permission.description}
                            </span>
                          </span>
                        </label>
                      );
                    })}
                  </div>
                </fieldset>
              );
            })}

            <fieldset>
              <legend className="text-[11.5px] font-semibold">Business units in scope</legend>
              <p className="mb-1.5 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                The only units a request from this role may read. A role that opens a data view needs at
                least one.
              </p>
              <label className="mb-1.5 flex cursor-pointer items-center gap-2 text-[12px]">
                <input
                  type="checkbox"
                  checked={draft.allBusinessUnits}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, allBusinessUnits: e.target.checked }))
                  }
                  className="h-3.5 w-3.5 cursor-pointer accent-[var(--series-1)]"
                />
                <span className="font-medium">Every business unit</span>
                <span className="text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                  — including any added later
                </span>
              </label>
              {!draft.allBusinessUnits && (
                <div className="flex flex-wrap gap-1.5">
                  {catalog.businessUnits.map((bu) => {
                    const checked = draft.businessUnits.includes(bu);
                    return (
                      <label
                        key={bu}
                        className="flex cursor-pointer items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px] transition-colors"
                        style={{
                          background: checked ? "var(--accent-wash)" : "var(--surface-2)",
                          border: `1px solid ${checked ? "var(--accent-ink)" : "var(--border-strong)"}`,
                          color: checked ? "var(--accent-ink)" : "var(--text-secondary)",
                        }}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => toggleBu(bu)}
                          className="h-3 w-3 cursor-pointer accent-[var(--series-1)]"
                        />
                        {bu}
                      </label>
                    );
                  })}
                </div>
              )}
            </fieldset>

            <div className="flex items-center gap-2">
              <Button
                variant="primary"
                onClick={() => void save()}
                disabled={busy || draft.name.trim() === "" || draft.permissions.length === 0}
              >
                {editing ? "Save changes" : "Create role"}
              </Button>
              <span
                className="text-[11px] font-medium"
                style={{ color: draft.permissions.length > 0 ? "var(--accent-ink)" : "var(--text-muted)" }}
              >
                {draft.permissions.length} permission{draft.permissions.length === 1 ? "" : "s"} ticked
              </span>
            </div>
          </div>
        )}
      </Panel>

      <Panel
        title="Roles"
        subtitle="Who holds each role, and what it opens. An address can be added before its owner has ever signed in."
      >
        {!roles ? (
          <Skeleton rows={4} />
        ) : roles.length === 0 ? (
          <p className="text-[12px]" style={{ color: "var(--text-muted)" }}>
            No custom roles yet. Everyone is on their tier defaults.
          </p>
        ) : (
          <div className="space-y-3">
            {roles.map((role) => (
              <div
                key={role.id}
                className="rounded-lg p-3"
                style={{ border: "1px solid var(--card-border)", background: "var(--surface-1)" }}
              >
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <h3 className="flex flex-wrap items-center gap-2 text-[13px] font-semibold">
                      {role.name}
                      {role.systemRole && (
                        <Badge tone="info" glyph="◆">
                          Built in
                        </Badge>
                      )}
                      {role.allBusinessUnits ? (
                        <Badge tone="neutral">All business units</Badge>
                      ) : (
                        <Badge tone="neutral">
                          {role.businessUnits.length} business unit
                          {role.businessUnits.length === 1 ? "" : "s"}
                        </Badge>
                      )}
                    </h3>
                    {role.description && (
                      <p
                        className="mt-0.5 text-[11px] leading-snug"
                        style={{ color: "var(--text-secondary)" }}
                      >
                        {role.description}
                      </p>
                    )}
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    <Button variant="secondary" onClick={() => startEdit(role)}>
                      Edit
                    </Button>
                    {!role.systemRole && (
                      <Button variant="danger" onClick={() => void remove(role)} disabled={busy}>
                        Delete
                      </Button>
                    )}
                  </div>
                </div>

                <div className="mt-2 flex flex-wrap gap-1">
                  {role.permissions.map((key) => (
                    <span
                      key={key}
                      className="rounded px-1.5 py-[2px] text-[10.5px]"
                      style={{ background: "var(--surface-2)", color: "var(--text-secondary)" }}
                    >
                      {labels.get(key) ?? key}
                    </span>
                  ))}
                </div>

                {!role.allBusinessUnits && role.businessUnits.length > 0 && (
                  <p className="mt-1.5 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                    Scope: {role.businessUnits.join(", ")}
                  </p>
                )}

                <div className="mt-2.5">
                  <h4 className="text-[11px] font-semibold" style={{ color: "var(--text-secondary)" }}>
                    Members
                  </h4>
                  {role.memberEmails.length === 0 ? (
                    <p className="mt-1 text-[11px]" style={{ color: "var(--text-muted)" }}>
                      Nobody holds this role yet.
                    </p>
                  ) : (
                    <ul className="mt-1 flex flex-wrap gap-1.5">
                      {role.memberEmails.map((email) => (
                        <li
                          key={email}
                          className="inline-flex items-center gap-1.5 rounded-full px-2 py-[3px] text-[11px]"
                          style={{
                            background: "var(--surface-2)",
                            border: "1px solid var(--border-hairline)",
                          }}
                        >
                          {email}
                          <button
                            type="button"
                            onClick={() => void removeMember(role, email)}
                            aria-label={`Remove ${email} from ${role.name}`}
                            title={`Remove ${email}`}
                            // Quiet until it is pointed at, then red: revoking someone's access is not an
                            // action to make eye-catching, but it should not be a surprise either.
                            className="leading-none transition-colors hover:text-[var(--status-critical-text)]"
                            style={{ color: "var(--text-muted)" }}
                          >
                            ×
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  <form
                    className="mt-2 flex flex-wrap items-center gap-2"
                    onSubmit={(e) => {
                      e.preventDefault();
                      void addMember(role);
                    }}
                  >
                    <input
                      type="email"
                      value={memberDraft[role.id] ?? ""}
                      onChange={(e) =>
                        setMemberDraft((d) => ({ ...d, [role.id]: e.target.value }))
                      }
                      placeholder="name@leadsquared.com"
                      className="min-w-0 flex-1 rounded-md px-2.5 py-1.5 text-[12px] sm:max-w-[18rem]"
                      style={{
                        background: "var(--surface-2)",
                        border: "1px solid var(--border-strong)",
                        color: "var(--text-primary)",
                      }}
                    />
                    <Button type="submit" variant="primary" disabled={busy}>
                      Grant this role
                    </Button>
                  </form>
                </div>
              </div>
            ))}
          </div>
        )}
      </Panel>

      <Panel
        title="Who has access"
        subtitle="Every address the application knows, and what it resolves to right now."
        note="An address shown as not yet signed in has been granted a role but has never authenticated. The grant is already live: it applies the first time they sign in."
      >
        {!people ? (
          <Skeleton rows={5} />
        ) : (
          <DataTable
            headers={[
              "Email",
              "Name",
              "Roles",
              { label: "Permissions", align: "right" },
              "Business units",
            ]}
            ariaLabel="Effective access by person"
          >
            {people.map((person) => (
              <tr key={person.email}>
                <Td>
                  <span className="block">{person.email}</span>
                  {!person.provisioned && (
                    <span className="text-[10px]" style={{ color: "var(--text-muted)" }}>
                      not yet signed in
                    </span>
                  )}
                  {person.lastLoginAt && (
                    <span className="text-[10px]" style={{ color: "var(--text-muted)" }}>
                      last in {formatDateTime(person.lastLoginAt)}
                    </span>
                  )}
                </Td>
                <Td>{person.displayName ?? "—"}</Td>
                <Td>
                  {person.customRoles.length === 0 ? (
                    <span style={{ color: "var(--text-muted)" }}>none — no access</span>
                  ) : (
                    person.customRoles.join(", ")
                  )}
                </Td>
                <Td align="right">{person.permissions.length}</Td>
                <Td>
                  {person.allBusinessUnits
                    ? "All"
                    : person.businessUnits.length === 0
                      ? "—"
                      : person.businessUnits.join(", ")}
                </Td>
              </tr>
            ))}
          </DataTable>
        )}
      </Panel>

    </div>
  );
}
