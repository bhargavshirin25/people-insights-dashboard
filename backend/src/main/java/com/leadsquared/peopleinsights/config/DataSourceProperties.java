package com.leadsquared.peopleinsights.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits on the data-source features: what an imported file and a polled endpoint may do.
 *
 * <p>These are ceilings rather than tuning. An administrator configures a URL and an interval on a screen,
 * so the values that decide how much memory one response can take, how long a request may hang, and how
 * often the poller wakes belong in configuration where an operator sets them — not in a form where the
 * person adding a feed does.
 */
@ConfigurationProperties(prefix = "dashboard.data-source")
public class DataSourceProperties {

  /** Master switch for the background poller. Off leaves the sources configured but idle. */
  private boolean pollingEnabled = true;

  /** How often the poller wakes to look for due sources. An interval is honoured to within one tick. */
  private int pollTickSeconds = 15;

  private int requestTimeoutSeconds = 20;

  /** Largest response body accepted, in bytes. */
  private int maxResponseBytes = 5_000_000;

  /** Records written from one run, after which the rest are dropped. */
  private int maxRecordsPerRun = 5_000;

  /**
   * Whether a configured URL may point at a loopback or private address.
   *
   * <p>True by default because pointing a source at a service on the same machine is the normal case while
   * building. In an environment where the server can reach things a configurer should not, turn it off:
   * this is the one setting that decides whether the feature can be used to probe the internal network.
   */
  private boolean allowPrivateHosts = true;

  public boolean isPollingEnabled() {
    return pollingEnabled;
  }

  public void setPollingEnabled(boolean pollingEnabled) {
    this.pollingEnabled = pollingEnabled;
  }

  public int getPollTickSeconds() {
    return pollTickSeconds;
  }

  public void setPollTickSeconds(int pollTickSeconds) {
    this.pollTickSeconds = pollTickSeconds;
  }

  public int getRequestTimeoutSeconds() {
    return requestTimeoutSeconds;
  }

  public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
    this.requestTimeoutSeconds = requestTimeoutSeconds;
  }

  public int getMaxResponseBytes() {
    return maxResponseBytes;
  }

  public void setMaxResponseBytes(int maxResponseBytes) {
    this.maxResponseBytes = maxResponseBytes;
  }

  public int getMaxRecordsPerRun() {
    return maxRecordsPerRun;
  }

  public void setMaxRecordsPerRun(int maxRecordsPerRun) {
    this.maxRecordsPerRun = maxRecordsPerRun;
  }

  public boolean isAllowPrivateHosts() {
    return allowPrivateHosts;
  }

  public void setAllowPrivateHosts(boolean allowPrivateHosts) {
    this.allowPrivateHosts = allowPrivateHosts;
  }
}
