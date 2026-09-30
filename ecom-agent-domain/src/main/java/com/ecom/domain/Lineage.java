package com.ecom.domain;

import java.util.List;
import java.util.Map;

/** Read-only SQL lineage, independent of SQL execution permission. */
public final class Lineage {
    private Lineage() {}

    public enum Transformation { DIRECT, COMPUTED, AGGREGATED }
    public record ModelSource(String model,String version,String sql,Result lineage) {}
    public interface Service {
        Map<String,List<String>> schema();
        Result analyze(String sql);
        ModelSource model();
    }

    public record SourceColumn(String table, String column) {}

    /** rowSources captures COUNT(*) and row-cardinality dependencies without inventing a source column. */
    public record ColumnLineage(String output, String expression, Transformation transformation,
                                List<SourceColumn> sources, List<String> rowSources) {
        public ColumnLineage {
            sources = List.copyOf(sources);
            rowSources = List.copyOf(rowSources);
        }
    }

    public record Predicate(String kind, String expression, List<SourceColumn> sources) {
        public Predicate { sources = List.copyOf(sources); }
    }

    public record Result(List<ColumnLineage> columns, List<String> sourceTables,
                         List<Predicate> predicates, List<String> limitations) {
        public Result {
            columns = List.copyOf(columns);
            sourceTables = List.copyOf(sourceTables);
            predicates = List.copyOf(predicates);
            limitations = List.copyOf(limitations);
        }
    }
}
