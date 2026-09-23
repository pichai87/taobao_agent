package com.ecom.tools;

import com.ecom.domain.BusinessException;
import java.util.Set;

/**
 * 首版安全策略：SQL 必须与已审核模板完全相同，参数通过 PreparedStatement 绑定。
 * 这是比“解析后随意放行 SELECT”更小的执行能力；未来 AST 方案需独立评审。
 */
public final class ApprovedSql {
    private ApprovedSql() {}
    public static final int MAX_RESULT_ROWS = 500;
    // Fetch one extra row so a bounded query cannot silently produce partial totals.
    public static final int FETCH_LIMIT = MAX_RESULT_ROWS + 1;
    public static final String DAILY = "SELECT stat_date, category, gmv, paid_orders, uv FROM analytics_daily WHERE stat_date = ? ORDER BY category LIMIT " + FETCH_LIMIT;
    public static final String TREND = "SELECT stat_date, category, gmv, paid_orders, uv FROM analytics_daily WHERE stat_date BETWEEN ? AND ? ORDER BY stat_date, category LIMIT " + FETCH_LIMIT;
    private static final Set<String> ALLOWED = Set.of(DAILY, TREND);
    public static String validate(String sql) {
        if (sql == null || !ALLOWED.contains(sql)) throw new BusinessException("SQL_NOT_APPROVED");
        return sql;
    }
}
