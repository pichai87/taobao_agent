package com.ecom.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** MDL = model definition language（语义模型）。仅声明受支持的逻辑字段，禁止嵌入任意 SQL。 */
public final class Semantic {
    private Semantic() {}
    public enum Formula { SUM, RATIO }
    public record MetricDef(String code, String name, Formula formula, String numerator,
                            String denominator, String unit, String description) {}
    public record Model(String name, String table, List<String> dimensions,
                        List<String> measures, List<MetricDef> metrics, String version) {}
    public record Query(List<String> metrics, List<String> dimensions, LocalDate start,
                        LocalDate end, String category, int limit) {}
    public record ColumnLineage(String output, List<String> inputs, String transformation) {}
    public record Compiled(Query plan, String sql, List<Object> parameters, List<ColumnLineage> lineage,
                           String modelVersion, String limitation) {}
    public interface Registry {
        Model model();
        MetricDef register(MetricDef definition);
    }
    public record KnowledgeHit(String id, String layer, String title, String text, String version) {}
    public interface Workbench {
        Model model();
        Compiled plan(Query query);
        List<Map<String,Object>> query(String owner, Query query);
        MetricDef register(String owner, MetricDef definition);
        List<KnowledgeHit> recall(String metric, String question);
        void remember(String owner, String summary);
        String memory(String owner);
    }
}
