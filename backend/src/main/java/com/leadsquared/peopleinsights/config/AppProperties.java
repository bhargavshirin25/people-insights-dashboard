package com.leadsquared.peopleinsights.config;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dashboard-wide settings.
 *
 * <p>{@code asOfDate} matters more than it looks. The HR Ops extract ends 31-Jul-2026, so
 * evaluating "last 30 days" against the wall clock would return an empty dashboard. Every
 * period-relative metric is computed against this anchor instead; leaving it blank makes the
 * anchor the latest attendance month found at ingest.
 */
@ConfigurationProperties(prefix = "dashboard")
public class AppProperties {

  private LocalDate asOfDate;

  /** Canonical BU list, used to reject unknown BU identifiers before they reach a query. */
  private List<String> businessUnits =
      List.of(
          "Customer Success",
          "Engineering",
          "Finance",
          "HR",
          "Marketing",
          "Operations",
          "Product",
          "Sales");

  private String confidentialityLabel = "Confidential — HR Operations — LeadSquared";

  /** Audit retention floor from the access-control rules. */
  private int auditRetentionDays = 90;

  /** Denied cross-BU attempts by one user within the window that trigger an anomaly alert. */
  private int anomalyDeniedThreshold = 5;

  private int anomalyWindowMinutes = 60;

  /** Enables the seeded username/password sign-in used until Entra SSO is configured. */
  private boolean devLoginEnabled = true;

  /**
   * Stand-in identity for the Microsoft sign-in button while there are no Entra tenant credentials.
   *
   * <p>This is an authentication bypass, and it is written down as one. Anyone who can reach the sign-in
   * page becomes this user, so it is live only while {@code dev-login-enabled} is true and Entra is
   * unconfigured — the {@code sso} profile turns both off and the button starts the real handshake
   * instead. The startup log says which of the two is in force.
   */
  private String placeholderSignInEmail = "nalamati.shirin@leadsquared.com";

  private String placeholderSignInName = "Nalamati Bhargav Shirin";

  /** Addresses provisioned into the built-in full-access role at startup. */
  private List<String> fullAccessEmails =
      List.of("hr.automation@leadsquared.com", "nalamati.shirin@leadsquared.com");

  /**
   * How many assembled datasets to hold in memory. Each entry is one authorised scope and filter
   * combination; an org-wide unfiltered entry is the largest at roughly 51,000 documents. Zero
   * disables caching, which makes every view re-read the collections — correct, but 15 to 80 seconds
   * a request against a remote cluster.
   */
  private int datasetCacheEntries = 16;

  /**
   * How long a cached dataset may be served before the latest successful ingest is re-checked. The
   * check is one indexed lookup, amortised across every request inside the window, and it is what
   * bounds staleness if the workbooks are re-ingested by the command-line runner while the server is
   * up. Zero re-checks on every request.
   */
  private int datasetCacheRevalidateSeconds = 30;

  public LocalDate getAsOfDate() {
    return asOfDate;
  }

  public void setAsOfDate(LocalDate asOfDate) {
    this.asOfDate = asOfDate;
  }

  public List<String> getBusinessUnits() {
    return businessUnits;
  }

  public void setBusinessUnits(List<String> businessUnits) {
    this.businessUnits = businessUnits == null ? List.of() : List.copyOf(businessUnits);
  }

  public String getConfidentialityLabel() {
    return confidentialityLabel;
  }

  public void setConfidentialityLabel(String confidentialityLabel) {
    this.confidentialityLabel = confidentialityLabel;
  }

  public int getAuditRetentionDays() {
    return auditRetentionDays;
  }

  public void setAuditRetentionDays(int auditRetentionDays) {
    this.auditRetentionDays = auditRetentionDays;
  }

  public int getAnomalyDeniedThreshold() {
    return anomalyDeniedThreshold;
  }

  public void setAnomalyDeniedThreshold(int anomalyDeniedThreshold) {
    this.anomalyDeniedThreshold = anomalyDeniedThreshold;
  }

  public int getAnomalyWindowMinutes() {
    return anomalyWindowMinutes;
  }

  public void setAnomalyWindowMinutes(int anomalyWindowMinutes) {
    this.anomalyWindowMinutes = anomalyWindowMinutes;
  }

  public String getPlaceholderSignInEmail() {
    return placeholderSignInEmail;
  }

  public void setPlaceholderSignInEmail(String placeholderSignInEmail) {
    this.placeholderSignInEmail = placeholderSignInEmail;
  }

  public String getPlaceholderSignInName() {
    return placeholderSignInName;
  }

  public void setPlaceholderSignInName(String placeholderSignInName) {
    this.placeholderSignInName = placeholderSignInName;
  }

  public List<String> getFullAccessEmails() {
    return fullAccessEmails;
  }

  public void setFullAccessEmails(List<String> fullAccessEmails) {
    this.fullAccessEmails = fullAccessEmails == null ? List.of() : List.copyOf(fullAccessEmails);
  }

  public boolean isDevLoginEnabled() {
    return devLoginEnabled;
  }

  public void setDevLoginEnabled(boolean devLoginEnabled) {
    this.devLoginEnabled = devLoginEnabled;
  }

  public int getDatasetCacheEntries() {
    return datasetCacheEntries;
  }

  public void setDatasetCacheEntries(int datasetCacheEntries) {
    this.datasetCacheEntries = datasetCacheEntries;
  }

  public int getDatasetCacheRevalidateSeconds() {
    return datasetCacheRevalidateSeconds;
  }

  public void setDatasetCacheRevalidateSeconds(int datasetCacheRevalidateSeconds) {
    this.datasetCacheRevalidateSeconds = datasetCacheRevalidateSeconds;
  }
}
