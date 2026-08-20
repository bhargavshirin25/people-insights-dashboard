/**
 * How each above-the-fold metric is derived, keyed by the card key the API returns.
 *
 * These sit here rather than inline in the tile because MetricTile renders whatever cards the
 * headline hands it, so the copy has to be addressable by key. Each entry mirrors the calculation in
 * `MetricsService` — if a definition changes there, change it here too: nothing fails if they drift.
 */
export type Methodology = {
  method: string;
  formula?: string;
  caveat?: string;
};

export const METRIC_METHODOLOGY: Record<string, Methodology> = {
  headcount: {
    method:
      "Employees active on roll at the reporting date, counted from joining and exit dates rather than from a stored status flag. The month-on-month figure compares that count against the last day of the previous month.",
    formula:
      "headcount = employees where joining date ≤ as-of date\n  AND (no exit date OR exit date > as-of date)",
    caveat:
      "Anchored to the reporting date, not today, so the count does not drift as the wall clock moves.",
  },
  attritionRolling3m: {
    method:
      "Voluntary exits over the trailing three months, divided by average headcount for that window, then annualised. Average headcount samples the window start plus each month end, so a team that grew or shrank mid-window is not measured against a single day's roster.",
    formula:
      "attrition % = voluntary exits / avg headcount × (12 / months) × 100",
    caveat:
      "Only Voluntary — Regrettable and Voluntary — Non-Regrettable count. Involuntary exits and absconding are excluded, since a rate that mixes them stops being a retention measure.",
  },
  attritionYtd: {
    method:
      "The same voluntary-attrition calculation as the rolling figure, but measured from the start of the financial year to the reporting date and annualised over that longer window.",
    formula:
      "attrition % = voluntary exits since FY start / avg headcount × (12 / months) × 100",
    caveat:
      "Early in a financial year the window is short, so annualising a handful of exits can produce a large and unstable rate.",
  },
  enps: {
    method:
      "Employee net promoter score for the latest survey cycle. Respondents are bucketed by their 0–10 score — promoters 9–10, passives 7–8, detractors 0–6 — and the score is the promoter share minus the detractor share.",
    formula: "eNPS = (promoters − detractors) / respondents × 100\nrange −100 to +100",
    caveat:
      "Derived from each response's numeric score, not the category label in the source workbook. Non-respondents are excluded from the base.",
  },
  atRisk: {
    method:
      "Employees the attrition risk model places in the medium or high band, scored from performance, engagement, tenure, leave and attendance signals. The comparison re-runs the whole model against the previous month end.",
    formula: "at-risk = employees whose risk score falls in the Medium or High band",
    caveat:
      "The movement is partial by nature: ratings and eNPS are point-in-time facts that do not change when the anchor moves, so month-on-month reflects attendance, leave and roster changes. See the risk register for per-employee factors.",
  },
  openPositions: {
    method:
      "Approved-but-unfilled headcount, summed across open requisitions for the business units in scope.",
    formula: "open positions = Σ vacant count where requisition status = OPEN",
    caveat:
      "Requisition history is not retained, so there is no month-on-month change. The card reports itself unavailable when no requisition data has been loaded, rather than showing zero.",
  },
};
