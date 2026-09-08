# Project overview — Robin Insights Dashboard

**Audience:** anyone evaluating, demoing or onboarding onto the project who does not need
implementation detail. Engineers building on the codebase should read
[`ARCHITECTURE.md`](ARCHITECTURE.md) next.

---

## What it is

Robin is a BU-level (business-unit-level) people-analytics dashboard for HR business partners
(HRBPs), HR leadership and CHRO at LeadSquared. It turns seven HR Ops data extracts —
headcount, compensation, leave, attendance, performance, engagement (eNPS) andM exits — into
metrics, an at-risk register, exit intelligence and an AI-written narrative, all gated by a
business-unit and permission model so a reader only ever sees the people data they are
authorised for.

It was built as a hackathon project and has since taken on a production-shaped access-control
and audit layer, because the data it touches (compensation, attrition risk, individual employee
records) is the most sensitive category HR holds.

**Stack:** Next.js 15 (React 19) frontend, Spring Boot 3.5 / Java 21 backend, MySQL 8.4 for
storage, Anthropic's Claude for narrative generation and the in-dashboard assistant.

---

## Who uses it, and how much they see

Access is role-based, and a role is defined on the `/access` screen rather than fixed in code —
see [`ACCESS-CONTROL.md`](ACCESS-CONTROL.md) for the full model. The shape most people will
recognise:

| Tier | Sees | Individual names/PII |
|---|---|---|
| HRBP | Their assigned business unit(s) only | Yes, within their BU |
| HR Head | Every business unit | Yes |
| CHRO | Every business unit, plus the org-wide heat map | Yes |
| Viewer | Aggregated metrics only | No |
| Admin | Configuration and data ingest only — no employee data at all | No |

A request for a business unit outside a caller's assignment returns `403`, never an empty
result — so probing for data by guessing a BU name is distinguishable from a genuine empty
dataset.

---

## The nine views

| View | Route | What it shows |
|---|---|---|
| BU overview | `/` | Six headline metrics with month-on-month movement, an AI narrative, a 30-day HR calendar, one-click PDF export |
| Attrition risk register | `/risk` | Employees ranked by risk, with the top three contributing factors each, and a place to log a retention action |
| Exit analysis | `/exit` | Exit themes, exit types, tenure bands, sentiment trend, anonymised verbatim comments |
| Performance & engagement | `/performance` | Three PMS review cycles, promotion rate, eNPS with theme breakdown, high performers never promoted |
| Leave & attendance | `/leave-attendance` | Leave utilisation, team attendance health, burnout and unplanned-absence flags |
| Org heat map | `/heatmap` | Every business unit side by side on the same metrics — HR Head and CHRO only |
| Audit trail | `/audit` | Every access event, granted or denied, plus anomalous-access alerts |
| Access | `/access` | Where roles, permissions and business-unit scope are defined |
| Data Source | `/data-source` | Where the underlying data comes from: workbook ingest, ad-hoc file import, polled API feeds |

All nine share one filter bar (grade, location, tenure, period); changing a filter moves every
card and chart on the page together, and the selection is remembered per user.

---

## What makes this more than a report

**Figures are never invented.** The AI narrative on the overview page can only cite numbers that
also appear on a metric card on that same page — every generated figure is checked against the
underlying fact sheet, and a narrative that cites something ungrounded is regenerated once, then
replaced by a deterministic (non-AI) summary. The figures the narrative was allowed to use are
shown to the reader on request, so a claim can be checked rather than trusted.

**A gap in the data is reported as a gap, not papered over.** Four things the original brief
asked for cannot be computed from the supplied extract (open requisitions, eNPS month-on-month,
contract renewal dates, transaction-based unplanned leave) — see
[`DATA-NOTES.md`](DATA-NOTES.md). Each renders an explicit "not available" state with the reason,
rather than a zero or an empty chart that would look like a real answer.

**Risk thresholds are calibrated to the data, not assumed.** An early version of the attrition
model used fixed thresholds and flagged 58% of one business unit — the assumption behind the
threshold was wrong for this population. Thresholds are now relative to the selection's own
distribution where that is the right comparison, and stay absolute where the number has meaning
outside the dataset (a PMS rating, a published compensation band). See "Why risk thresholds are
relative, not absolute" in `DATA-NOTES.md`.

**Ask Robin**, a chat assistant on every view, answers questions about the figures already on
screen. It is briefed with the same authorised, filtered dataset the page rendered from — it has
no database access of its own — so it cannot answer with anything the reader wasn't already
allowed to see.

---

## Current state and what is not yet production-ready

This is the section to read before pointing the instance at real employee data.

1. **Sign-in is a placeholder.** The "Sign in with Microsoft" button does not check a credential
   until Entra (Azure AD) tenant credentials are supplied — until then, anyone who reaches the
   page is signed in as a fixed placeholder identity holding full access. It is logged loudly on
   every startup and recorded distinctly in the audit trail, but it must not reach an environment
   with real data. See `ACCESS-CONTROL.md` §11.
2. **The business-unit-to-HRBP mapping is a seeded default**, not the real organisational
   mapping — deriving it from the data would have granted every HRBP org-wide access (each HRBP
   in the extract appears against employees in all eight business units). HR Ops needs to confirm
   the real assignment before go-live.
3. **A performance cache (`DatasetCache`) is a deviation from the access-control mandate as
   originally read**, and needs an explicit sign-off decision — amend the rule, narrow the cache
   to non-identifiable aggregates, or move the aggregation into SQL. It cannot leak data across an
   authorisation boundary (its key includes the authorised BU list), but it is shared state
   holding identifiable data, which is exactly what the rule was written to forbid. See
   `ACCESS-CONTROL.md` §6.
4. Full sign-off checklist: `ACCESS-CONTROL.md`, "Open items for sign-off".

---

## Where to go next

- Running it locally, seeded accounts, the reporting-date anchor: [`../README.md`](../README.md)
- Access, privacy and the eight mandatory rules: [`ACCESS-CONTROL.md`](ACCESS-CONTROL.md)
- What the source data does and does not support: [`DATA-NOTES.md`](DATA-NOTES.md)
- MySQL container, schema, indexing, the Mongo→MySQL migration: [`MYSQL.md`](MYSQL.md)
- System design, package layout, API surface, for engineers: [`ARCHITECTURE.md`](ARCHITECTURE.md)
