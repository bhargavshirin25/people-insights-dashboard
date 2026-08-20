package com.leadsquared.peopleinsights.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * JSON marshalling for the few short lists and maps that live in a single column.
 *
 * <p>A business-unit assignment, an ingest's warnings and its per-collection row counts are read only
 * ever as a whole and never queried across, so a child table would buy a join and nothing else. The
 * conversion is done here rather than through a Spring converter on purpose: converters are matched on
 * erased generic types, so one registered for {@code List<String>} is a live hazard to every other
 * {@code List<...>} property in the model.
 */
final class StringLists {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<Map<String, Integer>> COUNT_MAP = new TypeReference<>() {};

  private StringLists() {}

  static String toJson(List<String> values) {
    try {
      return JSON.writeValueAsString(values == null ? List.of() : values);
    } catch (Exception e) {
      throw new IllegalStateException("Could not write list as JSON: " + values, e);
    }
  }

  static List<String> fromJson(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return List.copyOf(JSON.readValue(json, STRING_LIST));
    } catch (Exception e) {
      throw new IllegalStateException("Could not read list from JSON: " + json, e);
    }
  }

  static String countsToJson(Map<String, Integer> counts) {
    try {
      return JSON.writeValueAsString(counts == null ? Map.of() : counts);
    } catch (Exception e) {
      throw new IllegalStateException("Could not write counts as JSON: " + counts, e);
    }
  }

  static Map<String, Integer> countsFromJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return Map.copyOf(JSON.readValue(json, COUNT_MAP));
    } catch (Exception e) {
      throw new IllegalStateException("Could not read counts from JSON: " + json, e);
    }
  }
}
