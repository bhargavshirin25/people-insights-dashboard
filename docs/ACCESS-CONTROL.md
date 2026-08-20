# Access control and data privacy architecture

**Status:** for HR Ops review and sign-off before go-live.

This document maps each of the eight mandatory access and privacy rules to where it is implemented
and how it can be verified. Where a rule is only partly satisfied, that is stated plainly rather than
glossed.

---

## The single enforcement point

Every rule below depends on one structural decision, so it is worth stating first.

`ScopeGuard` is the only class that turns a request parameter into a set of business units. It
returns a `Scoped` value carrying an authorised BU list, and **no service accepts a BU string** —
`DatasetLoader` and every metric service take the `Scoped` object or the list inside it. The only way
to obtain one is to pass the guard.

```java
// ScopeGuard.resolve — the chokepoint
if (!scope.allowsBu(canonical)) {
  audit.denied(scope, canonical, dataType, action, "BU outside assignment");
  throw new AccessDeniedForBuException(canonical, "Access denied: you are not assigned to …");
}
```

This is what makes "not just blocked in the UI, but at the API and data query layer" true. There is
no code path from an HTTP parameter to a SQL predicate that bypasses it, because the type system
does not permit one: a service cannot be handed a BU that has not been through the guard.

Frontend navigation hides views a role cannot use, but that is convenience only. Every endpoint
refuses an unauthorised caller independently, which the tests assert directly.

---

## 1. Role-based access — three tiers

Implemented in `Role`, `AccessScope` and the filter chain in `SecurityConfig`.

| Role | BU scope | Individual PII | Notes |
|---|---|---|---|
| `HRBP` | Only assigned BUs | Yes, within scope | Lands on their own BU by default |
| `HR_HEAD` | All BUs | Yes | Sees all HRBP action logs and at-risk registers |
| `CHRO` | All BUs | Yes | Adds the org-wide heat map |
| `VIEWER` | All BUs | **No** | Aggregated, anonymised metrics only |
| `ADMIN` | **None** | **No** | Configuration only |

Two points that are easy to get wrong and were handled explicitly:

**Business-unit scope is set on the access page, not by the roles that read the data.** It is part of a role, which requires `MANAGE_ACCESS` to create or change (section 10) — so an HR Head, who reads every business unit, cannot widen anybody's scope including their own, and the separation this rule was written for is unchanged. What moved is where it is configured: `/access` rather than `/admin`.

**Admin is not "above" HRBP.** `Role.ADMIN` has rank 0, and `ScopeGuard.resolve` rejects it outright
before any data query:

```java
if (scope.role() == Role.ADMIN) {
  audit.denied(scope, requestedBu, dataType, action, "ADMIN role has no employee-data access");
  throw new AccessDeniedForBuException(requestedBu,
      "The Admin role is limited to configuration and cannot read employee data.");
}
```

So whoever decides which HRBP sees which BU gains no ability to read any of it. Verified live: Admin
requesting `/api/dashboard/overview?bu=Engineering` returns 403.

### Where the BU-to-HRBP mapping comes from — and why not from the data

The employee master carries an `HRBP Name` against every employee, and the obvious implementation is
to derive scope from it. **That would have granted every HRBP org-wide access.** In the supplied
extract each of the ten named HRBPs appears against employees in all eight BUs:

```
'Priya Menon'      n=524  BUs={HR: 84, Marketing: 60, Product: 63, Finance: 60,
                             Sales: 72, Customer Success: 69, Engineering: 60, Operations: 56}
'Nikhil Bose'      n=523  BUs={Operations: 73, Marketing: 59, HR: 71, Engineering: 59, …}
```

That column describes a relationship, not an access assignment. Scope therefore lives in its own
`bu_assignments` table, written only by an Admin — which is what the rules call for anyway
("Admin Role → Configuration access: BU-to-HRBP mapping"). It is seeded with one BU per HRBP as a
starting default, and the admin console states in the UI that HR Ops must confirm the real mapping
before go-live. **This is the one item that needs an HR Ops decision rather than an engineering one.**

---

## 2. No cross-BU data leakage

Enforced at the query layer, as described above. Specifically tested:

| Attempt | Result |
|---|---|
| Engineering HRBP requests Sales attrition | `403 access_denied` |
| Engineering HRBP requests another BU's eNPS or exit themes | `403 access_denied` |
| Same BU in different casing (`sALES`) | `403` — canonicalised first, then checked |
| A BU identifier that does not exist | `403` — unknown is treated as unauthorised |
| HRBP requests "everything" (`bu=ALL`) | Returns only their assigned BUs |
| HRBP requests the org-wide heat map | `403` |
| HRBP logs a retention action against an employee in another BU | `403` — the employee's own BU is re-checked |

The response is deliberately **403 with a message, never an empty 200**. The rules require that
typing another BU's identifier returns access denied rather than empty data, and the distinction
matters operationally: an empty result is indistinguishable from a BU that genuinely has no rows, so
a probe would look like a legitimate query.

---

## 3. Individual employee PII — protected below HRBP level

`ScopeGuard.resolveIndividual` gates every individual-grain endpoint and refuses anything below HRBP
even when the BU itself would be readable:

- **At-risk register** (`/api/risk/**`) — names shown only to the assigned HRBP and to HR Head/CHRO.
- **Named leave lists** — the burnout and unplanned-absence lists are populated only for HRBP and
  above; a Viewer receives empty lists plus a note saying so, and still gets the team aggregates.
- **30-day HR calendar** (`/api/dashboard/overview`, `/api/dashboard/calendar`, exported decks) —
  every event names one person, so the event list goes only to HRBP and above. A Viewer receives the
  per-type counts and no rows. Blanking the name would not have been sufficient: a promotion on a
  given date at a given grade in a given team identifies one person without it.
- **Attendance** — reported per team, never per individual, and teams with fewer than five people are
  suppressed so a small team cannot be de-anonymised.

**Exit verbatims are structurally anonymous.** They come from the extract's already-anonymised NLP
sheet, and the API returns only the quote, theme, sentiment, exit type, tenure band and month. No
employee id, no name, and deliberately **no department or designation** — in a team of a handful of
people, a department plus a tenure band is enough to identify someone. The exit date is reduced to a
month for the same reason.

eNPS verbatims are used to derive theme aggregates and are never returned alongside an employee id.

---

## 4. Compensation — highest sensitivity

Compensation is handled by absence rather than by permission: **there is no individual-compensation
endpoint anywhere in the API.** `/api/insights/compensation-bands` returns grade-level aggregates
only, and suppresses any grade with fewer than five employees in scope.

This satisfies the rules in a way a permission check could not:

- No individual fixed CTC, variable pay or in-hand figure is reachable through the API.
- No comparison between named employees is constructible from it, because no named row exists.
- The exported deck contains no compensation figures at all.

Compa ratio appears as a *contributing factor* on the at-risk register ("Compa ratio 0.83 against the
grade band") — a band-relative position visible only to the HRBP for that employee's own BU and
above. If HR Ops considers even that too much, removing the `COMPA_LOW` factor from
`RiskScoringService` is a one-line change.

---

## 5. Authentication before data access

No endpoint except `/api/auth/login`, `/api/auth/session`, `/api/auth/dev-login` and the health check
is reachable unauthenticated; everything else is `authenticated()` in the filter chain, and
`ScopeGuard.currentScope()` throws `NotAuthenticatedException` if no verified principal is present.
Verified: an unauthenticated `/api/dashboard/overview` returns 401.

**SSO status — the one open item.** Entra (Azure AD) OIDC is implemented in `EntraOidcUserService`
and activates via the `sso` profile, but this instance has no tenant credentials, so a seeded
username/password sign-in is currently active. Go-live requires supplying the tenant id, client id and
secret and setting `dashboard.dev-login-enabled=false`, which the `sso` profile does by default.

Two things hold regardless of which path is used:

- Both produce the same `DashboardPrincipal`, so authorisation is identical either way.
- The IdP supplies **identity only**. Role and BU assignment come from this application's own
  collections, so a valid corporate login for an unprovisioned user is rejected rather than defaulted
  to a role, and authorities never come from IdP group claims — access cannot widen because a
  directory group changed.

Session expiry is `server.servlet.session.timeout` (default 30 minutes of inactivity), after which
re-authentication is required.

---

## 6. Session isolation

- Session state lives in the servlet session; nothing identifiable is held in application-scoped
  state.
- Sign-in invalidates any existing session and starts a new one (`sessionFixation().newSession()`
  plus an explicit invalidate in `AuthController`), so no state carries over from a previous user on
  a shared browser.
- Concurrent-session control allows one authenticated session per user.
- Responses are marked `no-store`, so no intermediary or shared cache retains employee data.
- Saved filter state is stored per user on their own record and never shared.

**This rule shaped a performance decision, and that decision has since been reversed — it needs HR Ops
sign-off.** The rule was originally read as forbidding a server-side cache keyed by BU and filters,
being "a shared cache between sessions containing identifiable employee data", so none was built and
org-wide views took 16–80 seconds against Atlas.

On 17 Aug 2026 a `DatasetCache` was added, taking those views from ~11s to under 0.1s. What it does and
does not do:

- **It cannot widen access.** The cache key includes the authorised business-unit list, which can only
  come from `ScopeGuard`. An entry loaded for one scope cannot be served to a caller with a different
  one, because the key would not match. Every read is still authorised and audited before the cache is
  consulted.
- **It is nonetheless shared state, in application scope, holding identifiable employee data** — which
  is the thing this rule names. Two sessions with the same authorised scope share one instance.

That leaves three options, and the choice is HR Ops': amend Rule 6 to permit a scope-keyed cache;
replace it with a cache of non-identifiable aggregates only, which this section already states would
be compliant; or push the aggregation into SQL so only computed figures travel — cheaper now that the
data layer is local MySQL rather than a remote cluster. Until one is chosen, the cache is a known
deviation from the mandate rather than an accepted design.

---

## 7. Audit trail

`AuditService` writes one document per data-access event, through `ScopeGuard`, so a read cannot be
audited-by-accident-omission: the same call that authorises also records.

Each event carries the user, role, business unit, data type (`HEADCOUNT`, `ATTRITION`,
`COMPENSATION`, `PERFORMANCE`, `ENPS`, `EXIT`, `LEAVE`, `ATTENDANCE`, `RISK_REGISTER`, `CALENDAR`,
`NARRATIVE`, `EXPORT`, `CONFIG`, `AUTH`), the action, the outcome (`GRANTED` / `DENIED`), a timestamp,
the request path and the source IP.

- **Retention: 90 days**, enforced by the `audit_events_retention` scheduled event in MySQL (it was a
  MongoDB TTL index before the 17 Aug 2026 cutover) rather than a cleanup job someone has to
  remember to run.
- **Readable by** Admin (HR Ops and HR Tech) plus HR Head and CHRO. An HRBP requesting
  `/api/audit/recent` gets 403.
- **Anomaly alerting** is implemented, not just retention. Repeated denials by one user inside a
  configurable window raise an alert on the `AUDIT_ALERT` logger and surface at
  `/api/audit/anomalies`. Verified live:

```
ALERT priya.menon@leadsquared.com (HRBP) 11 denials severity=Medium
      targeted BUs: [Finance, HR, Marketing, Operations, Product, Sales]
```

An audit write failure is logged as an error but does not mask the user's actual request outcome.

---

## 8. Export and sharing controls

The business review deck (`/api/export/deck`, PDF):

- Is built from the same `Scoped` dataset as the on-screen view, so it is scoped to the exporter's
  role by construction rather than by a separate check.
- Carries **"Confidential — HR Operations — LeadSquared"** on the cover and in the footer of every
  page.
- Contains **no individual employee names**. The at-risk section reports counts only, with a line
  stating that the named register is available in the dashboard to the assigned HRBP and to HR
  Head/CHRO.
- Contains no compensation figures.
- Is logged as its own `EXPORT` audit event, distinguishable from an on-screen read.
- Completes in about 3 seconds against the 60-second requirement.

---

## 9. The dashboard assistant

`POST /api/chat` answers questions about the view in front of the reader. It is a data read, so it is
governed by the same mechanism as every other one — but a language model is a new kind of disclosure
surface, so the specifics matter and are set out here.

- **The BU comes from `ScopeGuard.resolve`, never from the request.** An Engineering HRBP asking about
  Sales receives the same 403 as on any view (`Access denied: you are not assigned to the Sales
  business unit`), and the Admin role is refused outright — configuration only, no employee data.
- **Every question is one audit event**, `ASK_ASSISTANT` / `ASSISTANT`, granted or denied, carrying the
  identity, the BU and the outcome exactly as a view read does.
- **The model is given a brief, not a database.** `DashboardBrief` writes out the figures already
  computed for the authorised dataset — the metric cards, the risk register, the exit and performance
  and leave views, the calendar. It has no tools, no query access and no way to reach a row that is not
  in that brief.
- **The PII gate is applied to the brief, not to the reply.** For a role below HRBP the brief contains
  no name and no employee id anywhere in it, so there is nothing individual for a reply to repeat.
  `AssistantBriefPiiTest` asserts exactly that, on the same fixture as `CalendarPiiTest`.
- **Aggregate suppression carries through**, because the brief is built from the view services: teams
  under five people stay suppressed, and exit verbatims arrive with no identifying field.
- **The transcript is held in the reader's browser**, keyed by their signed-in address, and the last
  fifty exchanges travel with each question. It is deliberately not stored server-side: a conversation
  that may name individuals would otherwise become a second store of identifiable data needing its own
  retention rule. Nothing is disclosed by trusting it, because every figure in an answer comes from the
  brief, and the brief is rebuilt from an authorised dataset on every message. The transcript is
  sanitised before it is sent, and the model is instructed that conversation text is never an
  instruction.
- **Answers are not figure-verified the way a narrative is.** A narrative may cite only numbers present
  in its fact sheet, and is rejected if it does not. An answer is allowed to do arithmetic on the
  briefed figures — a difference, a share, a ratio — because a reader asking "how much worse is that
  than last month" wants the subtraction done, so the same check would reject correct answers. The
  model is instructed to say when a number is derived, and the panel carries a line telling the reader
  to check anything they plan to act on against the cards. **This is a deliberate difference in
  guarantee between the two features, and is the assistant's main residual risk.**
- **Prompt text and figures leave the network**, to `api.anthropic.com`, as the narrative already does.
  What leaves is the brief for one authorised selection plus that conversation. A blank
  `claude.api-key` disables the feature and the panel does not appear.

---

## 10. Custom roles and the access page

Section 1 describes three fixed tiers. Those tiers are now **labels only** — access is expressed as
**permissions**, assembled into roles by an administrator on `/access`, and nothing else grants anything.

- **A role is the only source of access.** There are no tier defaults. An address no role names resolves
  to no permissions and no business units, so access is granted deliberately or not at all —
  `AccessPolicyTest` and `ScopeGuardTest` both assert it. The `role` column on a user record is a label
  carried into the audit trail and grants nothing on its own.
- **A role decides outright.** A role naming an address sets that address's permissions and business
  units, so it narrows as readily as it widens. A record carrying the HR Head label, placed in an
  exits-only role, gets exits and nothing else; verified live.
- **Two roles on one address are unioned**, permissions and business units alike.
- **Membership is on the role, keyed by email.** Access can therefore be granted to a colleague who has
  never signed in; the grant applies the first time they do. It is not a claim from the IdP — the IdP
  supplies identity, this supplies authorisation, exactly as section 1 describes for tiers.
- **Enforcement is one list, in `SecurityConfig`.** Every data and configuration endpoint is matched by
  URL prefix to the permission it requires, so a new endpoint added under an existing prefix inherits
  that prefix's permission rather than silently defaulting to "any signed-in user". `ScopeGuard` still
  resolves the business units and still writes the audit entry; the permission decides whether the
  request reaches it at all.
- **Resolution is per request, not per session.** A permission removed on the access page is gone on the
  offender's next request, with no re-login: verified by revoking a role mid-session and watching the
  same cookie lose one view and regain another. A session outliving its grant is the gap this closes.
- **The PII gate is a permission now.** `SEE_INDIVIDUAL_PII` and `SEE_COMPENSATION` answer the questions
  sections 3 and 4 describe, so a role can hand someone the risk register with names withheld — which
  the tiers could not express.
- **Two invariants are held in the API, not on the screen.** A role that opens a data view cannot be
  saved with no business unit, since that grant would read as access and behave as a denial. And the
  seeded `Full access` role cannot be deleted or stripped of `MANAGE_ACCESS`, so no sequence of clicks
  leaves nobody able to grant access back.
- **Every change is audited** under the `ACCESS` data type — `CREATE_ROLE`, `UPDATE_ROLE`,
  `DELETE_ROLE`, `GRANT_ROLE`, `REVOKE_ROLE` — with the actor, the role and the address. A change here
  changes what somebody else can read, which makes it more sensitive than any single data read.
- **`MANAGE_ACCESS` is the permission that grants every other one.** It is listed last on the page with
  that warning attached. Treat it as the administrator equivalent of a shared password.

### The full-access role

`hr.automation@leadsquared.com` and `nalamati.shirin@leadsquared.com` are seeded into a built-in
`Full access` role: every permission, every business unit. Its permission set is rewritten to *all*
permissions at every start, so "full access" keeps meaning that as permissions are added. Its membership
is written when the role is created and re-added only if the list has been emptied — so removing a member
is permanent, but the application never boots with the access page unreachable.

This role deliberately holds both employee data and the configuration surfaces, which no fixed tier does.
That combination is worth naming at sign-off: it is the one identity on the instance for which the
separation between "reads people data" and "administers the system" does not hold.

### The data-source role

A second role, **Data source manager**, is seeded with `MANAGE_CONFIG` alone and no members. It exists to
make the separation above available rather than theoretical: whoever loads the workbooks, imports a file or
configures a polled feed can be given that job without being given anybody's employee data. Verified live —
the ingest status, the importable tables, the API sources and approved headcount answer, while the overview,
the risk register, exit analysis, compensation bands, the audit trail, the access page and the assistant all
return 403.

`VIEW_AUDIT` is deliberately excluded: the trail records who read which employee data, which a data-source
job has no reason to see. It is an ordinary role rather than a system one, so it can be renamed, re-scoped or
deleted; an empty copy returns at the next start, which grants nothing because a role grants nothing until an
address is named in it.

---

## 11. Sign-in is Microsoft-only, and the current button is a placeholder

The sign-in page offers one mechanism: **Sign in with Microsoft**. The seeded email-and-password form is
gone from the UI.

With Entra configured, the button starts the real OIDC handshake and everything in section 5 applies.
**Until then it does not authenticate at all.** With no tenant credentials, `POST /api/auth/microsoft`
signs the caller in as the configured placeholder identity —
`dashboard.placeholder-sign-in-email` / `-name`, currently Nalamati Bhargav Shirin — with **no credential
check of any kind**. Anyone who can reach the page becomes that user, and that user holds the full-access
role.

This is an authentication bypass and is treated as one:

- It is live only while `dashboard.dev-login-enabled` is true **and** Entra is unconfigured. The `sso`
  profile turns both off, at which point the same button does the real handshake.
- Every start logs a `WARN` naming the placeholder identity, so no deployment can quietly be in this
  state.
- It is recorded in the audit trail as `Placeholder Microsoft sign-in — Entra not configured`, never as
  an SSO sign-in, so no reader of the trail can mistake one for the other.
- There is no password sign-in to fall back to: `POST /api/auth/dev-login` has been removed and no account
  holds a password hash, so a known default password cannot be an alternative way in.

**This must not reach an environment holding real employee data.** Supplying the three Entra values and
running with `--spring.profiles.active=local,sso` is what closes it, and it is item 7 below.

---

## Verification

`mvn test` in `backend/` runs 65 tests. `ScopeGuardTest` covers the isolation rules in section 2 and
the tiering in sections 1 and 3; `NarrativeVerificationTest` covers the no-fabricated-figures rule;
`AssistantBriefPiiTest` and `ChatHistoryTest` cover the assistant in section 9; `AccessPolicyTest`
covers the resolution rules in section 10, and `SeededRolesTest` covers the shape of the two roles startup
provisions.

The live checks quoted throughout this document were run against the running application via the API,
using the seeded accounts, and are reproducible with the commands in the README.

---

## Open items for sign-off

| # | Item | Owner |
|---|---|---|
| 1 | Confirm the real BU-to-HRBP mapping in the admin console. The seeded default is one BU per HRBP and is not derived from the data, for the reason in section 1. | HR Ops |
| 2 | Supply Entra tenant credentials and disable the seeded sign-in, making SSO the only path in. | HR Tech |
| 3 | Decide whether compa ratio may appear as a risk factor to an HRBP (section 4). | HR Ops |
| 4 | Confirm 90 days is the intended audit retention, and who receives anomaly alerts. | HR Ops / HR Tech |
| 5 | Rule on the `DatasetCache` deviation in section 6: amend Rule 6, narrow the cache to non-identifiable aggregates, or replace it with SQL-side aggregation. | HR Ops + HR Tech |
| 6 | Accept the assistant's disclosure model in section 9 — a briefed model with no tools, audited per question, with derived arithmetic permitted where a narrative's figures are verified. | HR Ops |
| 7 | **Blocking for go-live.** Supply Entra credentials and run with the `sso` profile, which removes the placeholder sign-in in section 11. Until then the instance has no authentication. | HR Tech |
| 8 | Confirm who holds `MANAGE_ACCESS`, and whether the combined data-plus-administration full-access role in section 10 is acceptable or should be split in two. | HR Ops |
