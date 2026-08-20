package com.leadsquared.peopleinsights.config;

import com.leadsquared.peopleinsights.domain.AppUser;
import com.leadsquared.peopleinsights.domain.ApiSource;
import com.leadsquared.peopleinsights.domain.BuAssignment;
import com.leadsquared.peopleinsights.domain.CustomRole;
import com.leadsquared.peopleinsights.domain.NarrativeDoc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.mapping.event.AfterConvertCallback;

/**
 * Tells the five mutable aggregates that they have been read from the database.
 *
 * <p>Mongo assigned ids on insert, so a new user or assignment arrived with a null id and Spring Data
 * could tell new from existing by looking at it. MySQL cannot generate a VARCHAR key, so the id is
 * assigned in the constructor instead — which means a brand-new object and a loaded one both have one.
 * These callbacks flip the flag behind {@code isNew()} on load, so {@code save()} inserts a new record
 * and updates a loaded one, exactly as before. Without them every update would be issued as an INSERT
 * and fail on the primary key.
 */
@Configuration
public class JdbcPersistenceConfig {

  @Bean
  public AfterConvertCallback<AppUser> markAppUserPersisted() {
    return user -> {
      user.markPersisted();
      return user;
    };
  }

  @Bean
  public AfterConvertCallback<BuAssignment> markBuAssignmentPersisted() {
    return assignment -> {
      assignment.markPersisted();
      return assignment;
    };
  }

  @Bean
  public AfterConvertCallback<ApiSource> markApiSourcePersisted() {
    return source -> {
      source.markPersisted();
      return source;
    };
  }

  @Bean
  public AfterConvertCallback<CustomRole> markCustomRolePersisted() {
    return role -> {
      role.markPersisted();
      return role;
    };
  }

  @Bean
  public AfterConvertCallback<NarrativeDoc> markNarrativePersisted() {
    return narrative -> {
      narrative.markPersisted();
      return narrative;
    };
  }
}
