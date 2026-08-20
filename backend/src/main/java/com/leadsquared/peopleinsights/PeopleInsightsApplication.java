package com.leadsquared.peopleinsights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is on for the data-source poller, which is the only scheduled work in the application: it
 * wakes on a tick and calls whichever configured API endpoints are due.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PeopleInsightsApplication {

  public static void main(String[] args) {
    SpringApplication.run(PeopleInsightsApplication.class, args);
  }
}
