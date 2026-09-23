package com.ecom.tools;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Semantic.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** NL → 受控语义计划 → SQL。该编译器是 Java 本地语义层，不冒充已部署 WrenAI。 */
public final class SemanticSqlCompiler {
    public static final String VERSION = "local-mdl-v1";
    public static final Set<String> MEASURES = Set.of("gmv", "paid_orders", "uv");
    public static final Set<String> DIMENSIONS = Set.of("stat_date", "category");
    private static final String LIMITATION = "合成数据中品类客群互斥，UV 可相加；真实跨品类 UV 必须先去重。仅支持单事实模型的 SUM/RATIO，不接受自由 SQL。";

    public static void validateDefinition(MetricDef d) {
        if (d == null || d.code() == null || !d.code().matches("[A-Z][A-Z0-9_]{0,31}") ||
            DIMENSIONS.contains(d.code().toLowerCase(Locale.ROOT)) ||
            d.name() == null || d.name().isBlank() || d.name().length() > 64 || d.formula() == null ||
            d.numerator() == null || !MEASURES.contains(d.numerator()) || d.unit() == null || d.unit().length() > 32 ||
            d.description() == null || d.description().isBlank() || d.description().length() > 2000 ||
            (d.formula() == Formula.RATIO && (d.denominator() == null || !MEASURES.contains(d.denominator()))) ||
            (d.formula() == Formula.SUM && d.denominator() != null)) throw new BusinessException("INVALID_METRIC_DEFINITION");
    }
    public Compiled compile(Model model, Query q) {
        if (model == null || !"analytics_daily".equals(model.table()) || q == null ||
            q.metrics() == null || q.metrics().isEmpty() || q.metrics().size() > 8 || q.metrics().stream().anyMatch(Objects::isNull) ||
            new HashSet<>(q.metrics()).size() != q.metrics().size() || q.dimensions() == null ||
            q.dimensions().size() > 2 || q.dimensions().stream().anyMatch(Objects::isNull) || !DIMENSIONS.containsAll(q.dimensions()) ||
            new HashSet<>(q.dimensions()).size() != q.dimensions().size() ||
            q.start() == null || q.end() == null || q.end().isBefore(q.start()) ||
            ChronoUnit.DAYS.between(q.start(),q.end()) > 365 || q.limit() < 1 || q.limit() > 500 ||
            (q.category() != null && (q.category().isBlank() || q.category().length() > 32)))
            throw new BusinessException("INVALID_SEMANTIC_PLAN");
        var definitions = new HashMap<String,MetricDef>();
        model.metrics().forEach(d -> {validateDefinition(d);definitions.put(d.code(),d);});
        var selections = new ArrayList<String>();
        var lineage = new ArrayList<ColumnLineage>();
        for (String dimension : q.dimensions()) {
            selections.add(dimension);
            lineage.add(new ColumnLineage(dimension,List.of(model.table()+"."+dimension),"DIRECT"));
        }
        for (String metric : q.metrics()) {
            MetricDef d = definitions.get(metric);
            if (d == null) throw new BusinessException("METRIC_NOT_SUPPORTED");
            if (!q.start().equals(q.end()) && !q.dimensions().contains("stat_date") &&
                ("uv".equals(d.numerator()) || "uv".equals(d.denominator())))
                throw new BusinessException("UV_CROSS_DATE_AGGREGATION");
            String expression = aggregate(d.numerator());
            var inputs = new ArrayList<String>();inputs.add(model.table()+"."+d.numerator());
            if (d.formula() == Formula.RATIO) {
                expression = "1.0 * ("+expression+") / NULLIF("+aggregate(d.denominator())+", 0)";
                inputs.add(model.table()+"."+d.denominator());
            }
            selections.add(expression+" AS `"+metric.toLowerCase(Locale.ROOT)+"`");
            lineage.add(new ColumnLineage(metric,List.copyOf(inputs),d.formula().name()));
        }
        var parameters = new ArrayList<Object>();parameters.add(q.start());parameters.add(q.end());
        String sql = "SELECT "+String.join(", ",selections)+" FROM analytics_daily WHERE stat_date BETWEEN ? AND ?";
        if (q.category() != null) {sql += " AND category = ?";parameters.add(q.category());}
        if (!q.dimensions().isEmpty()) sql += " GROUP BY "+String.join(", ",q.dimensions())+" ORDER BY "+String.join(", ",q.dimensions());
        // 多取一行作超限哨兵，绝不把截断后的值当完整总额。
        sql += " LIMIT "+(q.limit()+1);
        validateGenerated(sql,sql);
        return new Compiled(q,sql,List.copyOf(parameters),List.copyOf(lineage),model.version(),LIMITATION);
    }
    private static String aggregate(String field) {
        // SQL SUM 会忽略 NULL；任一缺流量行都不能被静默排除出 UV/CVR 分母。
        return field.equals("uv") ? "CASE WHEN COUNT(uv) = COUNT(*) THEN SUM(uv) ELSE NULL END" : "SUM("+field+")";
    }
    /** AST（抽象语法树）确认语法/单 SELECT/表范围；再与可信编译结果逐字比对，拒绝额外子句。 */
    public void validateGenerated(String candidate, String trusted) {
        if (candidate == null || candidate.length() > 16000 || !candidate.equals(trusted))
            throw new BusinessException("SQL_PLAN_MISMATCH");
        try {
            var statement = CCJSqlParserUtil.parse(candidate);
            if (!(statement instanceof PlainSelect select) || !(select.getFromItem() instanceof Table table) ||
                !"analytics_daily".equals(table.getFullyQualifiedName()) || select.getJoins() != null ||
                select.getWhere() == null || select.getLimit() == null)
                throw new BusinessException("SQL_NOT_ALLOWED");
        } catch (BusinessException e) {throw e;}
        catch (Exception e) {throw new BusinessException("SQL_PARSE_FAILED");}
    }
}
