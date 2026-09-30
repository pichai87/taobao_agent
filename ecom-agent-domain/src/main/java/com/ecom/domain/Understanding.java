package com.ecom.domain;

import java.time.LocalDate;
import java.util.List;

/** Structured understanding preview. A result requiring clarification must never be executed. */
public final class Understanding {
    private Understanding() {}

    public enum Intent { QUERY, COMPARE, ATTRIBUTION, TREND, TOP_N, DEFINITION, OPS, OTHER }

    public record Clarification(String code, String message, List<String> candidates) {
        public Clarification { candidates = List.copyOf(candidates); }
    }

    public record Resolved(List<String> metrics, List<Intent> intents,
                           LocalDate date, LocalDate compareDate,
                           LocalDate rangeStart, LocalDate rangeEnd,
                           String category, Integer topN,
                           List<Clarification> clarifications, List<String> rules) {
        public Resolved {
            metrics = List.copyOf(metrics);
            intents = List.copyOf(intents);
            clarifications = List.copyOf(clarifications);
            rules = List.copyOf(rules);
        }
        public boolean ready() { return clarifications.isEmpty(); }
    }
}
