package com.ecom;

import com.ecom.domain.BusinessException;
import com.ecom.infrastructure.JdbcAnalyticsReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Isolated real H2 tests: never connect to the user's running application data. */
class AnalyticsReadIntegrityTest {
    private static final LocalDate DATE = LocalDate.of(2030, 1, 2);
    private JdbcTemplate db;
    private JdbcAnalyticsReader reader;

    @BeforeEach void createIsolatedSchema() {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:integrity_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__schema.sql"),
            new ClassPathResource("db/migration/V4__complete_analytics_grain.sql")).execute(source);
        db = new JdbcTemplate(source);
        reader = new JdbcAnalyticsReader(new JdbcTemplate(source), db);
    }

    @Test void orderOnlyGrainRemainsVisibleAndMissingTrafficFailsExplicitly() {
        db.update("INSERT INTO traffic_daily VALUES(?, ?, ?)", DATE, "present", 10);
        order(1, "present", "100.00", "PAID");
        order(2, "missing-traffic", "200.00", "PAID");

        assertThat(db.queryForObject("SELECT SUM(gmv) FROM analytics_daily WHERE stat_date=?",
            BigDecimal.class, DATE)).isEqualByComparingTo("300.00");
        assertThat(db.queryForObject("SELECT uv FROM analytics_daily WHERE category='missing-traffic'", Long.class))
            .isNull();
        assertThatThrownBy(() -> reader.query("missing-traffic-run", DATE))
            .isInstanceOf(BusinessException.class).hasMessage("DATA_COVERAGE_INCOMPLETE");
        assertThat(auditStatus("missing-traffic-run")).isEqualTo("FAILED");
    }

    @Test void trafficWithoutOrdersIsAnObservedZeroNotMissingData() {
        db.update("INSERT INTO traffic_daily VALUES(?, ?, ?)", DATE, "observed-zero", 0);
        var rows = reader.query("observed-zero-run", DATE);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().gmv()).isEqualByComparingTo("0");
        assertThat(rows.getFirst().orders()).isZero();
        assertThat(rows.getFirst().uv()).isZero();
        assertThat(auditStatus("observed-zero-run")).isEqualTo("SUCCEEDED");
    }

    @Test void unpaidOrdersDoNotCreateMissingTrafficGrains() {
        order(1, "unpaid", "200.00", "CANCELLED");
        assertThat(reader.query("unpaid-run", DATE)).isEmpty();
        assertThat(auditStatus("unpaid-run")).isEqualTo("SUCCEEDED");
    }

    @Test void exactlyFiveHundredRowsRemainComplete() {
        insertCategories(500);
        var rows = reader.query("boundary-run", DATE);
        assertThat(rows).hasSize(500);
        assertThat(rows.stream().map(row -> row.gmv()).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("500.00");
        assertThat(auditStatus("boundary-run")).isEqualTo("SUCCEEDED");
    }

    @Test void dailyOverLimitFailsInsteadOfReturningAFalseTotal() {
        insertCategories(501);
        assertThat(db.queryForObject("SELECT SUM(gmv) FROM analytics_daily", BigDecimal.class))
            .isEqualByComparingTo("501.00");
        assertThatThrownBy(() -> reader.query("overflow-run", DATE))
            .isInstanceOf(BusinessException.class).hasMessage("QUERY_RESULT_LIMIT_EXCEEDED");
        assertThat(auditStatus("overflow-run")).isEqualTo("FAILED");
    }

    @Test void trendUsesTheSameOverflowGuard() {
        insertCategories(501);
        assertThatThrownBy(() -> reader.trend("trend-overflow-run", DATE.minusDays(6), DATE))
            .isInstanceOf(BusinessException.class).hasMessage("QUERY_RESULT_LIMIT_EXCEEDED");
        assertThat(auditStatus("trend-overflow-run")).isEqualTo("FAILED");
    }

    private void insertCategories(int count) {
        for (int i = 1; i <= count; i++) {
            String category = "category-" + i;
            db.update("INSERT INTO traffic_daily VALUES(?, ?, ?)", DATE, category, 1);
            order(i, category, "1.00", "PAID");
        }
    }

    private void order(long id, String category, String amount, String status) {
        db.update("INSERT INTO biz_order VALUES(?, ?, ?, ?, ?, ?)", id, DATE, category,
            "synthetic-review-" + id, new BigDecimal(amount), status);
    }

    private String auditStatus(String runId) {
        return db.queryForObject("SELECT status FROM tool_call_audit WHERE run_id=?", String.class, runId);
    }
}
