package com.ecom;

import com.ecom.domain.Understanding.Clarification;
import com.ecom.domain.Understanding.Intent;
import com.ecom.domain.Understanding.Resolved;
import com.ecom.tools.QuestionUnderstanding;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionUnderstandingTest {
    // UTC is still September 23, but business time is already September 24.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T16:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    @ParameterizedTest @CsvSource({
        "支付成交额,GMV", "订单数量,ORDERS", "独立访客数,UV", "转化率,CVR", "客单价,AOV"
    })
    void supportedMetricAliasesResolveWithoutInventingAnExtraMetric(String alias, String code) {
        Resolved r = resolve("查询2026-09-12的" + alias);
        assertThat(r.ready()).isTrue();
        assertThat(r.metrics()).containsExactly(code);
        assertThat(r.date()).isEqualTo(LocalDate.of(2026, 9, 12));
    }

    @Test void multipleExplicitMetricsArePreservedAndASingleFormMetricCannotHideThem() {
        Resolved all = resolve("查询今天GMV、订单数、UV、转化率和客单价");
        assertThat(all.ready()).isTrue();
        assertThat(all.metrics()).containsExactly("GMV", "ORDERS", "UV", "CVR", "AOV");
        Resolved conflict = QuestionUnderstanding.resolve("查询今天GMV和UV", TODAY, null, "GMV", CLOCK);
        assertIssue(conflict, "METRIC_FORM_CONFLICT");
        assertThat(conflict.metrics()).containsExactly("GMV", "UV");
    }

    @Test void dateSpecificMetricsMustNotBecomeAllMetricsForBothDates() {
        assertIssue(resolve("今天GMV对比昨天UV"), "METRIC_DATE_BINDING_AMBIGUOUS");
        assertIssue(resolve("GMV今天对比UV昨天"), "METRIC_DATE_BINDING_AMBIGUOUS");
        assertIssue(resolve("今天GMV和UV对比昨天GMV"), "METRIC_DATE_BINDING_AMBIGUOUS");

        Resolved shared = resolve("今天对比昨天GMV和UV");
        assertThat(shared.ready()).isTrue();
        assertThat(shared.metrics()).containsExactly("GMV", "UV");
        assertThat(resolve("今天GMV和UV对比昨天GMV和UV").ready()).isTrue();
    }

    @ParameterizedTest @CsvSource({
        "2026-09-02,2026-09-02", "2026-9-2,2026-09-02", "2026年9月2日,2026-09-02", "今年9月2日,2026-09-02",
        "今天,2026-09-24", "昨天,2026-09-23", "前天,2026-09-22", "3天前,2026-09-21", "十天前,2026-09-14"
    })
    void absoluteAndRelativeDatesHaveExplicitDeterministicMeaning(String expression, String expected) {
        Resolved r = resolve("查询" + expression + "的GMV");
        assertThat(r.ready()).isTrue();
        assertThat(r.date()).isEqualTo(LocalDate.parse(expected));
        assertThat(r.rangeStart()).isEqualTo(r.date());
        assertThat(r.rangeEnd()).isEqualTo(r.date());
    }

    @ParameterizedTest @CsvSource({
        "最近7天,2026-09-18,2026-09-24", "过去七天,2026-09-18,2026-09-24",
        "近1天,2026-09-24,2026-09-24", "过去7天不含今天,2026-09-17,2026-09-23",
        "最近7天（不包括今天）,2026-09-17,2026-09-23", "过去7天截至昨天,2026-09-17,2026-09-23"
    })
    void relativeRangesStateWhetherTodayIsIncluded(String expression, String start, String end) {
        Resolved r = resolve(expression + "GMV趋势");
        assertThat(r.ready()).isTrue();
        assertThat(r.rangeStart()).isEqualTo(LocalDate.parse(start));
        assertThat(r.rangeEnd()).isEqualTo(LocalDate.parse(end));
        assertThat(r.rules()).anyMatch(rule -> rule.contains("Asia/Shanghai"));
    }

    @ParameterizedTest @ValueSource(strings = {"前3天GMV", "前三天GMV", "前几天GMV"})
    void aBareBeforeNDaysExpressionRequiresClarification(String question) {
        assertIssue(resolve(question), "DATE_RANGE_AMBIGUOUS");
    }

    @Test void noDateIsNeverSilentlyReplacedWithToday() {
        Resolved r = resolve("GMV多少");
        assertIssue(r, "MISSING_DATE");
        assertThat(r.date()).isNull();
        assertThat(r.rangeStart()).isNull();
        Resolved form = QuestionUnderstanding.resolve("GMV多少", LocalDate.of(2026, 9, 12), null, "GMV", CLOCK);
        assertThat(form.ready()).isTrue();
        assertThat(form.date()).isEqualTo(LocalDate.of(2026, 9, 12));
    }

    @Test void conflictingTargetComparisonAndMetricFieldsEachRequireClarification() {
        Resolved r = QuestionUnderstanding.resolve("昨天对比前天的订单数", TODAY, TODAY.minusDays(1), "GMV", CLOCK);
        assertIssue(r, "DATE_FORM_CONFLICT");
        assertIssue(r, "COMPARE_DATE_FORM_CONFLICT");
        assertIssue(r, "METRIC_FORM_CONFLICT");
    }

    @ParameterizedTest @CsvSource({
        "昨天对比前天GMV,2026-09-23,2026-09-22",
        "与昨天相比今天GMV,2026-09-24,2026-09-23",
        "对比日昨天目标日今天GMV,2026-09-24,2026-09-23",
        "昨天GMV和前天相比,2026-09-23,2026-09-22",
        "今天对比今天GMV,2026-09-24,2026-09-24"
    })
    void comparisonRolesFollowWordsRatherThanSortingDates(String question, String target, String previous) {
        Resolved r = resolve(question);
        assertThat(r.ready()).isTrue();
        assertThat(r.date()).isEqualTo(LocalDate.parse(target));
        assertThat(r.compareDate()).isEqualTo(LocalDate.parse(previous));
    }

    @Test void twoUnlabelledDatesNeedARoleOrAnExplicitMatchingForm() {
        assertIssue(resolve("对比昨天和前天GMV"), "DATE_ROLES_AMBIGUOUS");
        Resolved r = QuestionUnderstanding.resolve("对比昨天和前天GMV", TODAY.minusDays(1), TODAY.minusDays(2), "GMV", CLOCK);
        assertThat(r.ready()).isTrue();
        assertThat(r.compareDate()).isEqualTo(TODAY.minusDays(2));
    }

    @Test void dayOverDayAndYearOverYearAreOnlyInferredForExplicitDailyPeriods() {
        Resolved daily = resolve("今天GMV日环比");
        assertThat(daily.ready()).isTrue();
        assertThat(daily.compareDate()).isEqualTo(TODAY.minusDays(1));
        Resolved yearly = resolve("2026年9月12日GMV日同比");
        assertThat(yearly.ready()).isTrue();
        assertThat(yearly.compareDate()).isEqualTo(LocalDate.of(2025, 9, 12));
        assertIssue(resolve("今天GMV环比"), "COMPARISON_PERIOD_REQUIRED");
        assertIssue(resolve("今天GMV同比"), "COMPARISON_PERIOD_REQUIRED");
        assertIssue(resolve("2024-02-29GMV日同比"), "COMPARISON_DATE_AMBIGUOUS");
        assertIssue(QuestionUnderstanding.resolve("今天GMV日环比", TODAY, TODAY.minusDays(7), "GMV", CLOCK), "COMPARISON_PERIOD_CONFLICT");
    }

    @Test void explicitDateIntervalsAreNotMistakenForComparisonPairs() {
        Resolved r = resolve("2026年9月1日到2026年9月10日GMV趋势");
        assertThat(r.ready()).isTrue();
        assertThat(r.rangeStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(r.rangeEnd()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(r.compareDate()).isNull();
        assertThat(resolve("2026-01-01至2027-01-01GMV趋势").ready()).isTrue();
        assertIssue(resolve("2026-01-01至2027-01-02GMV趋势"), "INVALID_DATE_RANGE");
        assertIssue(resolve("2026-09-12至2026-09-11GMV趋势"), "INVALID_DATE_RANGE");
    }

    @ParameterizedTest @CsvSource({
        "2026-02-30GMV,INVALID_DATE", "9月12日GMV,DATE_YEAR_REQUIRED", "上月GMV,UNSUPPORTED_TIME_EXPRESSION",
        "最近367天GMV,INVALID_DAY_COUNT", "最近零天GMV,INVALID_DAY_COUNT", "昨天GMV趋势,MISSING_TREND_RANGE",
        "查询今天数据,MISSING_METRIC", "今天利润多少,UNSUPPORTED_METRIC"
    })
    void unsupportedOrIncompleteRequestsStayUnready(String question, String code) {
        assertIssue(resolve(question), code);
    }

    @Test void topNAndCategoryConstraintsRemainPartOfTheResult() {
        Resolved r = resolve("今天家电GMV前五名");
        assertThat(r.ready()).isTrue();
        assertThat(r.category()).isEqualTo("appliances");
        assertThat(r.topN()).isEqualTo(5);
        assertThat(r.intents()).contains(Intent.TOP_N);
        assertIssue(resolve("今天GMV Top 101"), "INVALID_TOP_N");
        assertIssue(resolve("今天家电和美妆GMV"), "MULTIPLE_CATEGORIES");
        assertIssue(resolve("今天GMV，品类=服装"), "UNSUPPORTED_CATEGORY");
        assertThat(resolve("今天所有品类包括家电美妆食品的GMV").category()).isNull();
    }

    @Test void distinctRequestedActionsAreNotCollapsedIntoOneIntent() {
        Resolved r = QuestionUnderstanding.resolve("解释GMV口径，再查询今天GMV排名并分析下降原因", TODAY, TODAY.minusDays(1), "GMV", CLOCK);
        assertThat(r.ready()).isTrue();
        assertThat(r.intents()).containsExactly(Intent.DEFINITION, Intent.QUERY, Intent.ATTRIBUTION, Intent.TOP_N);
        assertThat(resolve("GMV是什么").ready()).isTrue();
        assertThat(resolve("GMV同比口径是什么").intents()).containsExactly(Intent.DEFINITION);
        assertThat(resolve("GMV同比口径是什么").ready()).isTrue();
        assertThat(resolve("查看任务状态").intents()).containsExactly(Intent.OPS);
        assertThat(resolve("查看任务状态").ready()).isTrue();
    }

    @Test void multiplePeriodsAndConflictingRangeEndAreNotSilentlyCombined() {
        assertIssue(resolve("最近3天和最近7天GMV"), "MULTIPLE_DATE_RANGES");
        assertIssue(resolve("最近7天对比2026-09-01GMV"), "DATE_RANGE_CONFLICT");
        assertIssue(QuestionUnderstanding.resolve("最近7天GMV趋势", TODAY.minusDays(1), null, "GMV", CLOCK), "DATE_FORM_CONFLICT");
    }

    @ParameterizedTest @CsvSource({
        "今天除家电外的GMV,NEGATED_CONSTRAINT_UNSUPPORTED",
        "今天不含家电GMV,NEGATED_CONSTRAINT_UNSUPPORTED",
        "今天家电以外的GMV,NEGATED_CONSTRAINT_UNSUPPORTED",
        "今天非家电GMV,NEGATED_CONSTRAINT_UNSUPPORTED",
        "今天不要看家电GMV,NEGATED_CONSTRAINT_UNSUPPORTED",
        "今天不要GMV只看UV,NEGATED_CONSTRAINT_UNSUPPORTED",
        "今天GMV除以UV,METRIC_RELATION_UNSUPPORTED",
        "今天GMV Top 1e3,INVALID_TOP_N",
        "今天GMV Top 3-5,INVALID_TOP_N",
        "今天GMV前三至五名,INVALID_TOP_N"
    })
    void unsupportedConstraintOperatorsNeverBecomeReadyByIgnoringTheOperator(String question, String code) {
        assertIssue(resolve(question), code);
    }

    private static Resolved resolve(String question) {
        return QuestionUnderstanding.resolve(question, null, null, null, CLOCK);
    }

    private static void assertIssue(Resolved r, String code) {
        assertThat(r.ready()).isFalse();
        assertThat(r.clarifications()).extracting(Clarification::code).contains(code);
    }
}
