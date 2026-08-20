# Data notes

What the HR Ops extract actually contains, what it does not, and every decision taken where the two
disagreed. Read this before questioning a number on the dashboard.

---

## The files

The extract lives in `PEOPLE_INSIGHTS_DASHBOARD_DATA/` and is **seven `.xlsx` workbooks, not CSV**.
They are laid out for human readers: a title row, sometimes a subtitle, a merged group-header row
("Annual Compensation", "Monthly Breakdown"), and only then the real column names — several of which
contain embedded newlines and non-breaking spaces. The header row differs per sheet:

| File | Sheet | Header row | Rows |
|---|---|---|---|
| `Dummy_Employee_Master_Data.xlsx` | Employee Master Data | 1 | 5,000 |
| `Compensation_Records.xlsx` | Compensation Data | 4 | 5,000 |
| `Employee_Leave_Records.xlsx` | Leave Balance | 4 | 5,000 |
| `Employee_Leave_Records.xlsx` | Leave Transactions | 2 | 32,689 |
| `Attendance_Records.xlsx` | Attendance Data | 2 + 3 (month row, day row) | 5,000 × 181 days |
| `NPS_Data.xlsx` | eNPS Data | 3 | 5,000 |
| `Exit_Data.xlsx` | Exit Analysis | 4 | 1,476 |

`Employee_ID_Email_Map.xlsx` duplicates a column already in the master and is not ingested.

The loader ingests **0 warnings** on this extract, and every field the dashboard depends on is fully
populated. The only nulls are legitimate: 409 employees have no `lastIncrementDate` because they have
not had an increment yet.

---

## Where the seventh dataset is

The brief lists **Performance & Appraisal Data** as a separate dataset. It is not a separate file — it
is 18 columns inside the employee master, six per cycle for `FY2023-24`, `FY2024-25` and `FY2025-26`
(rating, appraisal date, promoted, pre/post-promotion designation, promotion date). All seven datasets
are present; they arrive in six files.

---

## Reshaping attendance

The attendance sheet is wide: 5,000 rows × 181 day-columns for February to July 2026, with the month
written once per group over merged cells and day headers like `01\nSun`.

One document per employee-day would be ~905,000 documents for metrics that are always read per
employee-month. It is stored as **one document per employee-month** (29,960 documents) holding the day
marks verbatim for future drill-down, with the counts every view needs precomputed at ingest.

The notation set comes from the workbook's own legend:

| Code | Meaning | Counted as |
|---|---|---|
| `P` | Present | Attendance |
| `A` | Absent (unplanned, no request) | Absent |
| `A,R` | Absent with regularisation request | Absent + regularisation |
| `SA` | Single punch absent | Absent + single punch |
| `SA,R` | Single punch, regularisation requested | Absent + single punch + regularisation |
| `U` | LOP — loss of pay, no request | LOP |
| `R,U` | LOP with regularisation request | LOP + regularisation |
| `L` | On approved leave | Leave |
| `0.5` | Half day | Half day (0.5 weight in attendance rate) |
| `WO` | Week off | Excluded from working days |

The per-day array is **not loaded** by the dashboard — it is the bulk of the collection and no metric
reads it. Loading it in full for an org-wide view exhausted a 2 GB heap.

---

## Dates are stored as ISO strings

Spring Data's default maps a `LocalDate` to midnight in the JVM's zone, so on an IST machine
`2026-08-01` persists as `2026-07-31T18:30Z`. Reading it back is lossless, but any `$month`
aggregation or raw range query lands in the wrong month for the first day of every month — exactly the
boundary attrition and exit metrics group on. A converter stores dates as `yyyy-MM-dd` instead, which
sorts and range-compares correctly regardless of timezone.

---

## The reporting date is 31 July 2026, not today

The attendance register ends 31 July 2026. Anchoring "last 30 days" on the wall clock would return an
empty dashboard. Every period-relative metric uses `dashboard.as-of-date`; blank tracks the latest
attendance month found at ingest.

Because of this, headcount is derived from joining and exit dates rather than the `Employee Status`
flag — otherwise "last month's headcount" would be this month's number repeated. This is why headcount
at 31 July 2026 reads 3,558 org-wide while `Status = Active` counts 3,524: 34 employees have exit dates
after the reporting date and were still on roll on it.

---

## What the data does not support

Four things the brief asks for cannot be computed from this extract. All four are reported as an
explicit unavailable state carrying the reason — never as a zero, an empty chart, or an invented
figure.

### 1. Open approved headcount positions

There is no requisition dataset. The metric card renders "Not available" with the reason, and
`open_positions` is an Admin-maintained collection that starts empty. Loading positions there, or
connecting an ATS feed, activates the card with no code change.

### 2. eNPS month-on-month and the three-cycle trend

The extract contains **one** survey cycle (June 2026, 5,000 responses). There is no prior period, so
no MoM change exists. The card shows the score and states why the comparison is absent; the trend
chart shows the single cycle with a note. Both upgrade automatically when more cycles are loaded — the
schema already keys eNPS by cycle.

This also affects the requested "teams with declining PMS ratings vs eNPS drop" correlation. A drop
needs two cycles, so the engagement side compares a team's eNPS *level* against the selection average
instead, and every flagged row states which comparison was used. It becomes a true period-on-period
drop once a second cycle exists.

### 3. Contract renewal dates

The master records `Employee Type` (607 Contractors, 380 Interns) but no contract end date, so contract
renewals cannot be derived. The HR calendar returns the other three event types — probation
confirmations, appraisal milestones, work anniversaries — and reports contract renewal as unavailable
with the reason. Adding a contract-end field to the master activates it.

### 4. Unplanned leave, from leave transactions

The brief asks for "employees with excessive unplanned leave in the past 3 months". Two properties of
the extract make a transaction-based measure impossible:

- **Every one of the 32,689 leave transactions was applied for before the leave began.** Zero have
  `appliedOn >= fromDate`, so "unplanned" has no transaction-level signal at all.
- **The transaction log ends 25 March 2026** while attendance runs to 31 July 2026, so the trailing
  three months contain no transactions whatsoever.

A transaction-based measure would have returned an empty list forever and looked like a working
feature. It is measured from the **attendance register** instead, whose notation records exactly this
state (`A` = absent, unplanned, no request; `U` = LOP, no request). The UI states this on the panel.

Relatedly, **no employee has zero approved leave across six months**, so the burnout list is
legitimately empty on this extract. The panel says so explicitly, distinguishing "genuinely nobody"
from "could not be computed".

---

## Why risk thresholds are relative, not absolute

The attrition-risk model started with absolute thresholds — attendance below 75%, six or more absent
days per quarter — and flagged **58% of the business unit**. A register with 247 of 426 employees on it
is not a register.

The cause was an assumption about the data that turned out to be wrong. Measured distributions:

| Signal | p10 | p25 | median | p75 | p90 |
|---|---|---|---|---|---|
| Attendance rate, trailing quarter | 69.7% | 72.9% | **77.8%** | 82.9% | 82.9% |
| Absent + LOP days, trailing quarter | 4 | 4 | **9** | 12 | 14 |

Median attendance is 77.8%, so "below 75%" is close to the middle of the distribution, and the median
employee already records nine absent-or-LOP days a quarter. Both thresholds were near-universal.

Attendance and absence are now thresholded against **the selection's own distribution** — bottom
decile and bottom quartile for attendance, above the 90th percentile for absence. That keeps the flag
meaningful whatever the underlying attendance culture, and it adapts automatically when real payroll
data replaces this extract. Signals with an external business meaning (PMS ratings, eNPS scores, compa
ratio against a published band) stayed absolute, because those thresholds mean something outside this
dataset.

Recalibrated result for Engineering: **51 of 426 active employees (12.0%)** on the register, of which
11 (2.6%) are High. Factor frequency is well spread rather than dominated by one signal:

```
PMS_LOW 38 · NO_PROMO_LONG_TENURE 26 · PMS_STEEP_DECLINE 24 · COMPA_LOW 22 · PMS_DECLINE 18
ENPS_DETRACTOR 18 · ATTENDANCE_SEVERE 14 · ENPS_STRONG_DETRACTOR 13 · ATTENDANCE_LOW 13
UNPLANNED_ABSENCE 9 · HIGH_PERF_NO_PROMO 1
```

The model is a transparent additive rule set with stated weights in one place
(`RiskScoringService`), not a fitted classifier — the register has to show an HRBP three named
contributing factors and stand up in a conversation with a manager, which a black-box score cannot do.
Every factor is derived from something the dashboard also displays.

---

## Smaller decisions worth knowing

- **Sentiment scores are normalised to 0–100.** The two exit sheets disagree: the NLP sheet stores
  0–1, the summary sheet 0–100. Values at or below 1.0 are scaled up at ingest.
- **The employee master's BU wins** over a satellite file's copy, since BU drives every access
  decision and one value per employee must be authoritative. A mismatch is recorded as an ingest
  warning; there were none.
- **Voluntary attrition excludes involuntary exits and absconding.** A rate that mixes them in stops
  being a retention measure. Rates are annualised, so a rolling three-month figure is comparable with
  a YTD one.
- **YTD means the Indian financial year** (1 April – 31 March), matching the FY labels in the PMS data.
- **eNPS themes are keyword-tagged, not modelled.** The eNPS data has free text but no themes, unlike
  the exit data. A deterministic keyword tagger over the same theme vocabulary keeps the two views
  comparable and reproducible, and does not send 5,000 comments to a model on every page load.
  Unmatched comments are tagged "Uncategorised" rather than forced into the nearest theme, and the UI
  labels the method as a heuristic.
- **Team-level reporting suppresses teams under five people**, so an aggregate cannot de-anonymise a
  small team.
- **The exit view widens to full history** when the selected period contains fewer than ten exits, and
  says so in the period label — a 30-day window on one BU can contain too few exits to read.
