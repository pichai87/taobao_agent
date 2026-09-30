package com.ecom;

import com.ecom.domain.Lineage.*;
import com.ecom.tools.SqlLineageAnalyzer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SqlLineageTest {
    private final SqlLineageAnalyzer analyzer = new SqlLineageAnalyzer();
    private static final Map<String,List<String>> SCHEMA = Map.of(
            "orders",List.of("id","category_id","amount","quantity","status"),
            "categories",List.of("id","name"),
            "archived_orders",List.of("id","amount"));

    @Test void qualifiedJoinAndAggregateSeparateValueFromFilterDependencies() {
        Result result = analyze("SELECT c.name AS category, SUM(o.amount) AS gmv FROM orders o JOIN categories c ON o.category_id=c.id WHERE o.status='PAID' GROUP BY c.name");
        assertThat(column(result,"gmv").sources()).containsExactly(new SourceColumn("orders","amount"));
        assertThat(column(result,"gmv").transformation()).isEqualTo(Transformation.AGGREGATED);
        assertThat(column(result,"category").sources()).containsExactly(new SourceColumn("categories","name"));
        assertThat(result.predicates()).anySatisfy(p -> {
            assertThat(p.kind()).isEqualTo("JOIN");
            assertThat(p.sources()).containsExactly(new SourceColumn("categories","id"),new SourceColumn("orders","category_id"));
        });
        assertThat(result.predicates()).anySatisfy(p -> {
            assertThat(p.kind()).isEqualTo("WHERE");
            assertThat(p.sources()).containsExactly(new SourceColumn("orders","status"));
        });
    }

    @Test void cteAndDerivedTableResolveToPhysicalColumns() {
        Result result = analyze("WITH paid AS (SELECT category_id, amount FROM orders WHERE status='PAID'), totals AS (SELECT category_id, SUM(amount) AS total FROM paid GROUP BY category_id) SELECT x.total AS gmv FROM (SELECT total FROM totals) x");
        assertThat(column(result,"gmv").sources()).containsExactly(new SourceColumn("orders","amount"));
        assertThat(column(result,"gmv").transformation()).isEqualTo(Transformation.AGGREGATED);
        assertThat(result.sourceTables()).containsExactly("orders");
        assertThat(result.predicates()).anyMatch(p -> p.kind().equals("WHERE"));
    }

    @Test void explicitCteColumnNamesAreResolvedByPosition() {
        Result result = analyze("WITH paid(value) AS (SELECT amount FROM orders) SELECT value FROM paid");
        assertThat(column(result,"value").sources()).containsExactly(new SourceColumn("orders","amount"));
    }

    @Test void unionCombinesSourcesByPositionAndKeepsFirstBranchName() {
        Result result = analyze("SELECT amount AS value FROM orders UNION ALL SELECT amount AS historical FROM archived_orders ORDER BY value");
        assertThat(column(result,"value").sources()).containsExactly(new SourceColumn("archived_orders","amount"),new SourceColumn("orders","amount"));
        assertThat(result.sourceTables()).containsExactly("archived_orders","orders");
        assertThat(result.columns()).hasSize(1);
    }

    @Test void schemaExpandsStarsAndCountStarHasOnlyRowDependency() {
        Result star = analyze("SELECT o.* FROM orders o");
        assertThat(star.columns()).extracting(ColumnLineage::output).containsExactly("id","category_id","amount","quantity","status");
        Result count = analyze("SELECT COUNT(*) AS rows FROM orders WHERE status='PAID'");
        assertThat(column(count,"rows").sources()).isEmpty();
        assertThat(column(count,"rows").rowSources()).containsExactly("orders");
        assertThat(count.predicates()).anyMatch(p -> p.sources().contains(new SourceColumn("orders","status")));
    }

    @Test void arithmeticConditionalAndRatioCollectEveryExpressionDependency() {
        Result result = analyze("SELECT SUM(CASE WHEN status='PAID' THEN amount * quantity ELSE 0 END) / NULLIF(COUNT(id),0) AS metric FROM orders");
        assertThat(column(result,"metric").sources()).containsExactly(new SourceColumn("orders","amount"),new SourceColumn("orders","id"),
                new SourceColumn("orders","quantity"),new SourceColumn("orders","status"));
        assertThat(column(result,"metric").transformation()).isEqualTo(Transformation.AGGREGATED);
    }

    @Test void unrelatedCteIsNotInventedAsAnUpstreamDependency() {
        Result result = analyze("WITH unused AS (SELECT name FROM categories) SELECT amount FROM orders");
        assertThat(result.sourceTables()).containsExactly("orders");
        assertThat(column(result,"amount").sources()).containsExactly(new SourceColumn("orders","amount"));
    }

    @Test void ambiguousAndUnknownNamesFailInsteadOfGuessing() {
        rejected("SELECT id FROM orders o JOIN categories c ON o.category_id=c.id","LINEAGE_AMBIGUOUS_COLUMN");
        rejected("SELECT secret FROM orders","LINEAGE_UNKNOWN_COLUMN");
        rejected("SELECT amount FROM private_orders","LINEAGE_UNKNOWN_TABLE");
        rejected("SELECT orders.amount FROM orders o","LINEAGE_UNKNOWN_QUALIFIER");
        rejected("SELECT * FROM orders o JOIN categories c ON o.category_id=c.id","LINEAGE_DUPLICATE_OUTPUT");
    }

    @Test void recursiveAndForwardCteReferencesCannotResolveAsPhysicalTables() {
        rejected("WITH RECURSIVE tree AS (SELECT id FROM orders UNION ALL SELECT id FROM tree) SELECT id FROM tree","LINEAGE_UNSUPPORTED_CTE");
        rejected("WITH orders AS (SELECT amount FROM orders) SELECT amount FROM orders","LINEAGE_CTE_CYCLE_OR_FORWARD_REFERENCE");
        rejected("WITH a AS (SELECT value FROM b), b AS (SELECT amount AS value FROM orders) SELECT value FROM a","LINEAGE_CTE_CYCLE_OR_FORWARD_REFERENCE");
    }

    @Test void writeAndMultiStatementInputsAreNeverAccepted() {
        rejected("DELETE FROM orders","LINEAGE_SELECT_ONLY");
        rejected("SELECT amount FROM orders; SELECT name FROM categories","LINEAGE_SELECT_ONLY");
        rejected("SELECT amount INTO backup FROM orders","LINEAGE_SELECT_ONLY");
    }

    @Test void unsupportedSqlFeaturesAreExplicitErrors() {
        rejected("SELECT ROW_NUMBER() OVER (ORDER BY id) AS n FROM orders","LINEAGE_UNSUPPORTED_EXPRESSION");
        rejected("SELECT o.amount FROM orders o NATURAL JOIN categories c","LINEAGE_UNSUPPORTED_JOIN");
        rejected("SELECT o.amount FROM orders o JOIN categories c USING(id)","LINEAGE_UNSUPPORTED_JOIN");
        rejected("SELECT unknown_udf(amount) AS value FROM orders","LINEAGE_UNSUPPORTED_FUNCTION");
        rejected("SELECT (SELECT MAX(amount) FROM archived_orders) AS value FROM orders","LINEAGE_UNSUPPORTED_SUBQUERY");
        rejected("SELECT amount FROM orders WHERE id IN (SELECT id FROM archived_orders)","LINEAGE_UNSUPPORTED_SUBQUERY");
    }

    @Test void mismatchedUnionAndUnaliasedComputedOutputsAreRefused() {
        rejected("SELECT amount FROM orders UNION SELECT id, amount FROM archived_orders","LINEAGE_UNION_ARITY");
        rejected("SELECT SUM(amount) FROM orders","LINEAGE_EXPRESSION_ALIAS_REQUIRED");
        rejected("SELECT amount FROM orders INTERSECT SELECT amount FROM archived_orders","LINEAGE_UNSUPPORTED_SET_OPERATION");
    }

    @Test void aliasesAreScopedAndDoNotLeakBetweenQueries() {
        Result result = analyze("SELECT a.value AS first_value, b.value AS second_value FROM (SELECT amount AS value FROM orders) a JOIN (SELECT amount AS value FROM archived_orders) b ON a.value=b.value");
        assertThat(column(result,"first_value").sources()).containsExactly(new SourceColumn("orders","amount"));
        assertThat(column(result,"second_value").sources()).containsExactly(new SourceColumn("archived_orders","amount"));
    }

    @Test void nullSchemaAndDepthBoundariesAreEnforced() {
        assertThatThrownBy(() -> analyzer.analyze("SELECT * FROM orders",Map.of())).hasMessage("LINEAGE_INVALID_SCHEMA");
        assertThatThrownBy(() -> analyzer.analyze(" ",SCHEMA)).hasMessage("LINEAGE_INVALID_SQL");
        String nested = "SELECT amount FROM orders";
        for (int i = 0; i < 20; i++) nested = "SELECT amount FROM (" + nested + ") q" + i;
        rejected(nested,"LINEAGE_DEPTH_LIMIT");
    }

    @Test void quotedIdentifiersAndStringLiteralsAreNotMisreadAsTableColumns() {
        Result result = analyze("SELECT `amount` AS `value`, 'secret.other_column' AS note FROM `orders` WHERE status IN ('PAID','DONE')");
        assertThat(column(result,"value").sources()).containsExactly(new SourceColumn("orders","amount"));
        assertThat(column(result,"note").sources()).isEmpty();
        assertThat(result.sourceTables()).containsExactly("orders");
    }

    private Result analyze(String sql) { return analyzer.analyze(sql,SCHEMA); }
    private void rejected(String sql,String code) { assertThatThrownBy(() -> analyze(sql)).hasMessage(code); }
    private static ColumnLineage column(Result result,String output) {
        return result.columns().stream().filter(c -> c.output().equals(output)).findFirst().orElseThrow();
    }
}
