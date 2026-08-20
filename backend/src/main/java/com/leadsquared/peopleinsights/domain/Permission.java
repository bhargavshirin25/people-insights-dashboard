package com.leadsquared.peopleinsights.domain;

import java.util.List;

/**
 * One thing a user may do. The unit a custom role is assembled from.
 *
 * <p>The fixed {@link Role} tiers remain what a user is; permissions are what a user may reach. A tier
 * maps to a default permission set ({@code AccessPolicy}), so every existing account behaves exactly as
 * it did before this existed — a custom role is an override, not a replacement for the tiers.
 *
 * <p>Each constant carries its own label and description because the access page renders a checkbox per
 * permission. Keeping that copy here rather than in the frontend means a new permission cannot be added
 * to the enforcement layer and then quietly go missing from the screen where it is granted.
 */
public enum Permission {

  // ------------------------------------------------------------------ views
  VIEW_OVERVIEW(
      Group.VIEWS,
      "BU overview",
      "The landing view: the six metric cards, the AI summary and the 30-day HR calendar."),
  VIEW_RISK(
      Group.VIEWS,
      "Attrition risk register",
      "The ranked at-risk register and the contributing factors behind each score."),
  VIEW_EXIT(
      Group.VIEWS,
      "Exit analysis",
      "Exit themes, types, tenure bands, sentiment trend and the anonymised verbatims."),
  VIEW_PERFORMANCE(
      Group.VIEWS,
      "Performance & engagement",
      "PMS cycles, promotion rate, eNPS and the performance-versus-engagement flags."),
  VIEW_LEAVE_ATTENDANCE(
      Group.VIEWS,
      "Leave & attendance",
      "Leave utilisation, team attendance health and the absence anomaly flags."),
  VIEW_HEATMAP(
      Group.VIEWS,
      "Org heat map",
      "Every business unit on the same metrics, side by side. Spans the whole organisation."),
  VIEW_AUDIT(
      Group.VIEWS,
      "Audit trail",
      "Who read what, anomalous-access alerts and the 24-hour summaries."),

  // ------------------------------------------------------------------ data grain
  SEE_INDIVIDUAL_PII(
      Group.DATA,
      "Individual employee data",
      "Names, employee ids and individual rows. Without this, every view is aggregated and anonymised."),
  SEE_COMPENSATION(
      Group.DATA,
      "Compensation figures",
      "Individual salary, compa ratio and the compensation bands. The most sensitive field in the set."),

  // ------------------------------------------------------------------ actions
  USE_ASSISTANT(
      Group.ACTIONS,
      "Ask Robin",
      "The dashboard assistant. It answers only from the figures the same user could already read."),
  EXPORT_DECK(
      Group.ACTIONS, "Export the business review deck", "Download the PDF deck for the view in scope."),
  LOG_RETENTION_ACTION(
      Group.ACTIONS,
      "Log retention actions",
      "Record a retention conversation or action against an at-risk employee."),
  EDIT_NARRATIVE(
      Group.ACTIONS,
      "Regenerate and edit the AI summary",
      "Force a fresh summary, or edit one inline before it goes into a review."),

  // ------------------------------------------------------------------ administration
  MANAGE_CONFIG(
      Group.ADMINISTRATION,
      "Administration",
      "BU-to-HRBP mapping, user records, approved headcount and the data refresh."),
  MANAGE_ACCESS(
      Group.ADMINISTRATION,
      "Manage access",
      "Create roles, choose what they may reach, and assign them to email addresses. "
          + "Grant with care: it is the permission that can grant every other one.");

  /** How the access page groups the checkboxes. */
  public enum Group {
    VIEWS("Views", "Which screens the role can open."),
    DATA("Data grain", "How much of the underlying data the role sees inside those screens."),
    ACTIONS("Actions", "What the role can do beyond reading."),
    ADMINISTRATION("Administration", "Configuration surfaces. These carry no employee data of their own.");

    private final String label;
    private final String description;

    Group(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  private final Group group;
  private final String label;
  private final String description;

  Permission(Group group, String label, String description) {
    this.group = group;
    this.label = label;
    this.description = description;
  }

  public Group group() {
    return group;
  }

  public String label() {
    return label;
  }

  public String description() {
    return description;
  }

  /** The permissions that let a caller read employee data at all. */
  public static List<Permission> dataViews() {
    return List.of(
        VIEW_OVERVIEW, VIEW_RISK, VIEW_EXIT, VIEW_PERFORMANCE, VIEW_LEAVE_ATTENDANCE, VIEW_HEATMAP);
  }

  /** Parses a stored or submitted key, ignoring anything unrecognised rather than failing the request. */
  public static java.util.Optional<Permission> of(String key) {
    if (key == null) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(valueOf(key.trim().toUpperCase()));
    } catch (IllegalArgumentException e) {
      return java.util.Optional.empty();
    }
  }
}
