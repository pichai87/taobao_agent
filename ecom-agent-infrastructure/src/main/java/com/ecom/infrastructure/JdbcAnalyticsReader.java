package com.ecom.infrastructure;

import com.ecom.domain.Analysis.Daily;
import com.ecom.domain.Ports.AnalyticsReader;
import com.ecom.tools.ApprovedSql;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.List;

/** 读库连接与任务写库分离；生产账号只能 SELECT 已授权视图。 */
public class JdbcAnalyticsReader implements AnalyticsReader {
    private final JdbcTemplate reader;
    private final JdbcTemplate writer;
    public JdbcAnalyticsReader(JdbcTemplate reader, JdbcTemplate writer) {
        this.reader = reader;
        this.writer = writer;
        reader.setQueryTimeout(5);
        reader.setMaxRows(500);
    }
    public List<Daily> query(String runId, LocalDate date) {
        return execute(runId, ApprovedSql.DAILY, date);
    }
    public List<Daily> trend(String runId, LocalDate start, LocalDate end) {
        return execute(runId, ApprovedSql.TREND, start, end);
    }
    private List<Daily> execute(String runId, String sql, Object... args) {
        ApprovedSql.validate(sql);
        long start = System.nanoTime();
        List<Daily> rows;
        try {
            rows = reader.query(sql, (rs,n) -> new Daily(rs.getDate("stat_date").toLocalDate(),
                rs.getString("category"), rs.getBigDecimal("gmv"), rs.getLong("paid_orders"), rs.getLong("uv")), args);
        } catch (RuntimeException error) {
            audit(runId, sql, 0, "FAILED", start);
            throw error;
        }
        audit(runId, sql, rows.size(), "SUCCEEDED", start);
        return rows;
    }
    private void audit(String id, String sql, int count, String status, long start) {
        writer.update("INSERT INTO tool_call_audit(run_id,tool_name,sql_template,row_count,status,elapsed_ms) VALUES(?,?,?,?,?,?)",
            id, "analytics.read", sql, count, status, (System.nanoTime()-start)/1_000_000);
    }
}

