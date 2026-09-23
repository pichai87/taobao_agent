package com.ecom;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Semantic.*;
import com.ecom.tools.SemanticSqlCompiler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adversarial contract tests maintained independently of the compiler implementation. */
class SemanticBoundaryTest {
    private static final LocalDate DATE = LocalDate.of(2030, 1, 2);
    private final SemanticSqlCompiler compiler = new SemanticSqlCompiler();

    private static Model model() {
        return new Model("review_daily", "analytics_daily", List.of("stat_date", "category"),
            List.of("gmv", "paid_orders", "uv"), List.of(
                metric("GMV", Formula.SUM, "gmv", null),
                metric("ORDERS", Formula.SUM, "paid_orders", null),
                metric("UV", Formula.SUM, "uv", null),
                metric("CVR", Formula.RATIO, "paid_orders", "uv"),
                metric("AOV", Formula.RATIO, "gmv", "paid_orders")), "review-v1");
    }

    private static MetricDef metric(String code, Formula formula, String numerator, String denominator) {
        return new MetricDef(code, code, formula, numerator, denominator, "test-unit", "Synthetic review definition");
    }

    @Test void categoryTextIsAlwaysBoundAsData() {
        String injected = "x' OR 1=1 --";
        var result = compiler.compile(model(), new Query(List.of("GMV"), List.of("category"), DATE, DATE, injected, 10));
        assertThat(result.sql()).doesNotContain(injected).contains("category = ?");
        assertThat(result.parameters()).containsExactly(DATE, DATE, injected);
    }

    @Test void modifiedSqlCannotReuseAValidPlan() {
        var result = compiler.compile(model(), new Query(List.of("GMV"), List.of(), DATE, DATE, null, 10));
        assertThatThrownBy(() -> compiler.validateGenerated(result.sql() + " UNION SELECT 1", result.sql()))
            .isInstanceOf(BusinessException.class).hasMessage("SQL_PLAN_MISMATCH");
        assertThatThrownBy(() -> compiler.validateGenerated(result.sql().replace("analytics_daily", "biz_order"), result.sql()))
            .isInstanceOf(BusinessException.class).hasMessage("SQL_PLAN_MISMATCH");
    }

    @Test void unsupportedDimensionsAndMeasuresFailBeforeExecution() {
        assertThatThrownBy(() -> compiler.compile(model(), new Query(List.of("GMV"), List.of("customer_ref"), DATE, DATE, null, 10)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> compiler.compile(model(), new Query(List.of("SECRET"), List.of(), DATE, DATE, null, 10)))
            .isInstanceOf(BusinessException.class).hasMessage("METRIC_NOT_SUPPORTED");
        assertThatThrownBy(() -> SemanticSqlCompiler.validateDefinition(metric("INJECTED", Formula.SUM, "gmv); DROP TABLE biz_order; --", null)))
            .isInstanceOf(BusinessException.class).hasMessage("INVALID_METRIC_DEFINITION");
    }

    @Test void duplicateFieldsInvalidDatesAndLimitsAreRejected() {
        assertThatThrownBy(() -> compiler.compile(model(), new Query(List.of("GMV", "GMV"), List.of(), DATE, DATE, null, 10)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> compiler.compile(model(), new Query(List.of("GMV"), List.of("category", "category"), DATE, DATE, null, 10)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> compiler.compile(model(), new Query(List.of("GMV"), List.of(), DATE, DATE.minusDays(1), null, 10)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> compiler.compile(model(), new Query(List.of("GMV"), List.of(), DATE, DATE, null, 501)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> compiler.compile(model(), new Query(List.of("GMV"), Collections.singletonList(null), DATE, DATE, null, 10)))
            .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"CATEGORY", "STAT_DATE"})
    void metricAliasesCannotOverwriteDimensionOutputs(String reserved) {
        assertThatThrownBy(() -> SemanticSqlCompiler.validateDefinition(metric(reserved, Formula.SUM, "gmv", null)))
            .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"UV", "CVR"})
    void multiDayUvIsNotMislabelledAsDeduplicatedVisitors(String metric) {
        assertThatThrownBy(() -> compiler.compile(model(), new Query(List.of(metric), List.of("category"), DATE.minusDays(1), DATE, null, 10)))
            .isInstanceOf(BusinessException.class);
    }

    @Test void aCustomRatioUsingUvHasTheSameCrossDateRestriction() {
        MetricDef custom = metric("VALUE_PER_VISITOR", Formula.RATIO, "gmv", "uv");
        Model definition = new Model("review_daily", "analytics_daily", List.of("stat_date", "category"),
            List.of("gmv", "paid_orders", "uv"), List.of(custom), "review-v1");
        assertThatThrownBy(() -> compiler.compile(definition,
            new Query(List.of(custom.code()), List.of(), DATE.minusDays(1), DATE, null, 10)))
            .isInstanceOf(BusinessException.class);
    }

    @Test void dailyUvGroupsAndCrossDateMoneyRemainSupported() {
        assertThat(compiler.compile(model(), new Query(List.of("UV", "CVR"), List.of("stat_date"), DATE.minusDays(1), DATE, null, 10)).sql())
            .contains("GROUP BY stat_date");
        assertThat(compiler.compile(model(), new Query(List.of("GMV", "ORDERS", "AOV"), List.of(), DATE.minusDays(1), DATE, null, 10)).sql())
            .contains("SUM(gmv)");
    }

    @Test void conversionUsesRatioOfSumsNotAverageOfCategoryRates() {
        var db = data();
        db.update("INSERT INTO analytics_daily VALUES(?, ?, ?, ?, ?)", DATE, "small", 100, 1, 10);
        db.update("INSERT INTO analytics_daily VALUES(?, ?, ?, ?, ?)", DATE, "large", 100, 1, 990);
        var plan = compiler.compile(model(), new Query(List.of("CVR"), List.of(), DATE, DATE, null, 10));
        var value = db.queryForObject(plan.sql(), BigDecimal.class, plan.parameters().toArray());
        assertThat(value).isEqualByComparingTo("0.002");
    }

    @Test void aZeroDenominatorReturnsUnknownRatherThanInventedZero() {
        var db = data();
        db.update("INSERT INTO analytics_daily VALUES(?, ?, ?, ?, ?)", DATE, "empty", 0, 0, 0);
        var plan = compiler.compile(model(), new Query(List.of("AOV"), List.of(), DATE, DATE, null, 10));
        assertThat(db.queryForObject(plan.sql(), BigDecimal.class, plan.parameters().toArray())).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"UV", "CVR"})
    void missingUvCannotDisappearInsideAnAggregate(String metric) {
        var db = data();
        db.update("INSERT INTO analytics_daily VALUES(?, ?, ?, ?, ?)", DATE, "present", 100, 1, 10);
        db.update("INSERT INTO analytics_daily VALUES(?, ?, ?, ?, ?)", DATE, "missing", 200, 1, null);
        var plan = compiler.compile(model(), new Query(List.of(metric), List.of(), DATE, DATE, null, 10));
        assertThat(db.queryForObject(plan.sql(), BigDecimal.class, plan.parameters().toArray())).isNull();
        var money = compiler.compile(model(), new Query(List.of("GMV"), List.of(), DATE, DATE, null, 10));
        assertThat(db.queryForObject(money.sql(), BigDecimal.class, money.parameters().toArray()))
            .isEqualByComparingTo("300.00");
    }

    private static JdbcTemplate data() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:semantic_review_" + UUID.randomUUID() +
            ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE analytics_daily(stat_date DATE, category VARCHAR(32), gmv DECIMAL(18,2), paid_orders BIGINT, uv BIGINT)");
        return db;
    }
}
