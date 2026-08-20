/** Mirrors the backend response shapes. Kept hand-written so a field rename fails typecheck. */

export type Role = "VIEWER" | "HRBP" | "HR_HEAD" | "CHRO" | "ADMIN";

export interface Session {
  authenticated: boolean;
  email?: string;
  displayName?: string;
  role?: Role;
  assignedBus: string[];
  defaultBusinessUnit?: string | null;
  canSeeIndividualPii: boolean;
  orgWide: boolean;
  devLoginEnabled: boolean;
  ssoConfigured: boolean;
  savedFilterJson?: string | null;
  /** The resolved permission set. The API enforces the same set independently. */
  permissions: PermissionKey[];
  /** Custom roles that decided it; empty when the fixed tier's default did. */
  customRoles: string[];
}

/**
 * Kept as a union rather than `string` so a typo in a permission check fails the typecheck instead of
 * silently hiding a screen. Mirrors the backend `Permission` enum.
 */
export type PermissionKey =
  | "VIEW_OVERVIEW"
  | "VIEW_RISK"
  | "VIEW_EXIT"
  | "VIEW_PERFORMANCE"
  | "VIEW_LEAVE_ATTENDANCE"
  | "VIEW_HEATMAP"
  | "VIEW_AUDIT"
  | "SEE_INDIVIDUAL_PII"
  | "SEE_COMPENSATION"
  | "USE_ASSISTANT"
  | "EXPORT_DECK"
  | "LOG_RETENTION_ACTION"
  | "EDIT_NARRATIVE"
  | "MANAGE_CONFIG"
  | "MANAGE_ACCESS";

export interface MetricCard {
  key: string;
  label: string;
  value: number | null;
  displayValue: string;
  unit?: string | null;
  momAbsolute?: number | null;
  momPercent?: number | null;
  direction: "up" | "down" | "flat";
  sentiment: "green" | "amber" | "red";
  higherIsBetter: boolean;
  available: boolean;
  unavailableReason?: string | null;
  basis?: string | null;
}

export interface Headline {
  businessUnit: string;
  asOf: string;
  periodLabel: string;
  cards: MetricCard[];
  employeesInScope: number;
}

export interface Narrative {
  id: string;
  businessUnit: string;
  text: string;
  anomalies: { metric: string; severity: "High" | "Medium" | "Low"; description: string }[];
  citedFigures: { label: string; value: string }[];
  model?: string;
  generatedAt: string;
  editedBy?: string | null;
  editedByName?: string | null;
  editedAt?: string | null;
  fallback: boolean;
}

export interface CalendarEvent {
  date: string;
  type: "PROBATION_CONFIRMATION" | "APPRAISAL_MILESTONE" | "WORK_ANNIVERSARY";
  typeLabel: string;
  title: string;
  employeeId: string;
  employeeName?: string;
  grade?: string;
  department?: string;
  daysFromNow: number;
}

export interface CalendarView {
  businessUnit: string;
  from: string;
  to: string;
  /** Empty when eventsVisible is false — the role may not see individual events. */
  events: CalendarEvent[];
  typeCounts: Record<string, number>;
  typeLabels: Record<string, string>;
  totalEvents: number;
  eventsVisible: boolean;
  eventsNote?: string | null;
  unavailableEventTypes: { type: string; reason: string }[];
}

export interface Overview {
  headline: Headline;
  calendar: CalendarView;
  dataAsOf?: string | null;
  confidentialityLabel: string;
  accessibleBusinessUnits: string[];
  role: Role;
}

export interface FilterOptions {
  businessUnits: string[];
  grades: string[];
  locations: string[];
  tenureMaxYears: number;
  periods: string[];
  asOf: string;
}

export interface RiskFactor {
  code: string;
  label: string;
  detail: string;
  weight: number;
}

export interface RiskAssessment {
  employeeId: string;
  fullName?: string;
  grade?: string;
  designation?: string;
  department?: string;
  vertical: string;
  tenureYears?: number | null;
  score: number;
  band: "Low" | "Medium" | "High";
  factors: RiskFactor[];
  allFactors: RiskFactor[];
}

export interface RetentionAction {
  id: string;
  employeeId: string;
  vertical: string;
  actionType: string;
  actionDate: string;
  notes?: string;
  loggedByEmail: string;
  loggedByName?: string;
  loggedAt: string;
}

export interface RegisterView {
  businessUnit: string;
  totalActive: number;
  highCount: number;
  mediumCount: number;
  rows: { assessment: RiskAssessment; loggedActions: RetentionAction[] }[];
  methodology: string;
}

export interface ExitView {
  businessUnit: string;
  periodLabel: string;
  periodFrom?: string | null;
  periodTo?: string | null;
  totalExits: number;
  voluntaryExits: number;
  regrettableExits: number;
  avgTenureMonths: number;
  topThemes: {
    theme: string;
    mentions: number;
    pctOfExits: number;
    negativePct: number;
    neutralPct: number;
    positivePct: number;
    avgScore: number;
    sentiment: string;
  }[];
  exitTypes: { exitType: string; count: number; pct: number }[];
  tenureBands: { tenureBand: string; count: number; pct: number }[];
  sentimentTrend: { month: string; exits: number; avgScore: number }[];
  themeTrend: { month: string; avgScoreByTheme: Record<string, number> }[];
  verbatims: {
    quote: string;
    theme?: string;
    sentiment?: string;
    exitType?: string;
    tenureBand?: string;
    month?: string;
  }[];
  availableThemes: string[];
}

export interface PerformanceView {
  businessUnit: string;
  pmsTrend: { cycle: string; avgRating: number; rated: number; distribution: Record<string, number> }[];
  promotions: {
    promotionsLast12Months: number;
    promotionRatePct: number;
    eligibleHeadcount: number;
    basis: string;
  };
  enpsTrend: {
    cycle: string;
    score: number;
    responses: number;
    promoters: number;
    passives: number;
    detractors: number;
  }[];
  enpsThemes: {
    theme: string;
    mentions: number;
    avgScore: number;
    promoters: number;
    detractors: number;
    netSentiment: number;
  }[];
  enpsThemeMethod: string;
  correlation: {
    department: string;
    headcount: number;
    pmsChange?: number | null;
    latestAvgRating?: number | null;
    enpsScore?: number | null;
    flagged: boolean;
    reason?: string | null;
  }[];
  highPerformersNotPromoted: {
    employeeId: string;
    fullName?: string;
    grade?: string;
    designation?: string;
    department?: string;
    tenureYears?: number | null;
    ratings: number[];
  }[];
  enpsTrendNote?: string | null;
}

export interface LeaveAttendanceView {
  businessUnit: string;
  monthsCovered: string[];
  leaveUtilisation: {
    leaveType: string;
    entitlement: number;
    taken: number;
    utilisationPct: number;
    lapsed: number;
  }[];
  lopDaysTotal: number;
  teamHealth: {
    team: string;
    headcount: number;
    attendanceHealthScore: number;
    presentRatePct: number;
    halfDayRatePct: number;
    absentRatePct: number;
    lopRatePct: number;
    leaveRatePct: number;
    singlePunchDays: number;
    regularisationRequests: number;
  }[];
  anomalies: {
    team: string;
    metric: string;
    value: number;
    selectionMean: number;
    severity: "High" | "Medium" | "Low";
    detail: string;
  }[];
  zeroLeaveEmployees: EmployeeLeaveRow[];
  zeroLeaveNote?: string | null;
  excessiveUnplannedLeave: EmployeeLeaveRow[];
  unplannedLeaveNote?: string | null;
  individualListsVisible: boolean;
  individualListsNote?: string | null;
}

export interface EmployeeLeaveRow {
  employeeId: string;
  fullName?: string;
  grade?: string;
  department?: string;
  value?: number | null;
  detail: string;
}

export interface HeatMapView {
  asOf: string;
  rows: {
    businessUnit: string;
    headcount: number;
    attritionRolling3m: number;
    attritionYtd: number;
    enps?: number | null;
    atRiskCount: number;
    atRiskPct: number;
    attendanceRatePct: number;
    exitsInPeriod: number;
  }[];
  metrics: string[];
}

export interface CompensationView {
  businessUnit: string;
  gradeBands: {
    grade: string;
    headcount: number;
    bandMin: number;
    bandMidpoint: number;
    bandMax: number;
    avgCompaRatio: number;
    bandPositionCounts: Record<string, number>;
  }[];
  disclosure: string;
}


export interface AuditEventRow {
  id: string;
  at: string;
  userEmail: string;
  role?: Role | null;
  businessUnit: string;
  dataType: string;
  action: string;
  outcome: "GRANTED" | "DENIED";
  detail?: string | null;
  requestPath?: string | null;
  sourceIp?: string | null;
}

/** One message in the assistant conversation, as the browser holds it. */
export interface ChatTurn {
  role: "user" | "assistant";
  text: string;
}

/** The assistant's reply to one question. */
export interface ChatAnswer {
  text: string;
  model: string;
  businessUnit: string;
  asOf: string;
  periodLabel: string;
  /** Messages of the conversation the backend sent as context, the new question included. */
  contextMessages: number;
  /** The model declined to answer, so `text` is a standing message rather than a reply. */
  declined: boolean;
  /** The reply hit the token ceiling and stops mid-thought. */
  truncated: boolean;
}

export interface ChatAvailability {
  available: boolean;
}

/** ------------------------------------------------------------------ access page */

export interface PermissionInfo {
  key: PermissionKey;
  label: string;
  description: string;
  group: string;
}

export interface PermissionGroupInfo {
  key: string;
  label: string;
  description: string;
}

export interface AccessCatalog {
  groups: PermissionGroupInfo[];
  permissions: PermissionInfo[];
  businessUnits: string[];
}

export interface CustomRoleView {
  id: string;
  name: string;
  description?: string | null;
  permissions: PermissionKey[];
  businessUnits: string[];
  allBusinessUnits: boolean;
  memberEmails: string[];
  /** Maintained by the application: it cannot be deleted or stripped of "Manage access". */
  systemRole: boolean;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

export interface PersonAccess {
  email: string;
  displayName?: string | null;
  customRoles: string[];
  permissions: PermissionKey[];
  businessUnits: string[];
  allBusinessUnits: boolean;
  /** False for an address granted a role that has not signed in yet. */
  provisioned: boolean;
  lastLoginAt?: string | null;
}

/** ------------------------------------------------------------------ data source page */

export interface DataStatus {
  lastRun: string;
  dataAsOf: string;
  counts: Record<string, number>;
  warnings: string[];
  sourceDirectory: string;
  asOfOverride: string;
}

export interface OpenPosition {
  id: string;
  requisitionId?: string;
  vertical: string;
  department?: string;
  designation?: string;
  grade?: string;
  location?: string;
  approvedCount: number;
  filledCount: number;
  approvedOn?: string;
  targetCloseDate?: string;
  status: string;
}

export interface ColumnInfo {
  name: string;
  type: string;
  nullable: boolean;
  /** The database fills this in when a row omits it, so the file need not carry it. */
  autoOrDefaulted: boolean;
  primaryKey: boolean;
}

export interface TableInfo {
  name: string;
  columns: ColumnInfo[];
  rowCount: number;
}

export interface ImportReport {
  table: string;
  mode: "APPEND" | "REPLACE";
  mappedColumns: string[];
  skippedHeadings: string[];
  unmappedColumns: string[];
  rowsRead: number;
  rowsWritten: number;
  rowsRejected: number;
  deletedFirst: number;
  fileTruncated: boolean;
  errors: string[];
}

export interface ApiSourceRecord {
  id: string;
  name: string;
  url: string;
  jsonPath?: string | null;
  keyField?: string | null;
  intervalSeconds: number;
  enabled: boolean;
  replaceEachRun: boolean;
  lastRunAt?: string | null;
  lastStatus?: "OK" | "FAILED" | null;
  lastMessage?: string | null;
  lastRowCount?: number | null;
}

export interface ApiSourceView {
  source: ApiSourceRecord;
  rowsHeld: number;
  headers: Record<string, string>;
}

export interface SourceTest {
  ok: boolean;
  statusCode: number;
  elapsedMillis: number;
  contentType?: string | null;
  bytes: number;
  records: number;
  shape: string;
  fields: string[];
  preview: string;
  message: string;
}

/** A landed record, as the table stores it — snake_case because it comes straight from SQL. */
export interface FeedRow {
  record_index: number;
  record_key?: string | null;
  fetched_at: string;
  payload_json: string;
}
