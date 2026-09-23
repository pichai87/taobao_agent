package com.ecom.infrastructure;

import com.ecom.domain.Analysis.Daily;
import com.ecom.domain.BusinessException;
import com.ecom.domain.Ports.AnalyticsReader;
import com.ecom.tools.ApprovedSql;
import com.ecom.tools.SemanticSqlCompiler;
import com.ecom.domain.Semantic;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.List;

/** 读库连接与任务写库分离；生产账号只能 SELECT 已授权视图。 */
public class JdbcAnalyticsReader implements AnalyticsReader {
    private final JdbcTemplate reader;
    private final JdbcTemplate writer;
    private final Semantic.Workbench semantic;
    public JdbcAnalyticsReader(JdbcTemplate reader, JdbcTemplate writer) {
        this(reader,writer,null);
    }
    public JdbcAnalyticsReader(JdbcTemplate reader, JdbcTemplate writer,Semantic.Workbench semantic) {
        this.reader = reader;
        this.writer = writer;
        this.semantic = semantic;
        reader.setQueryTimeout(5);
        reader.setMaxRows(ApprovedSql.FETCH_LIMIT);
    }
    public List<Daily> query(String runId, LocalDate date) {
        if(semantic!=null) return executeSemantic(runId,date,date);
        return execute(runId, ApprovedSql.DAILY, date);
    }
    public List<Daily> trend(String runId, LocalDate start, LocalDate end) {
        if(semantic!=null) return executeSemantic(runId,start,end);
        return execute(runId, ApprovedSql.TREND, start, end);
    }
    private List<Daily> execute(String runId, String sql, Object... args) {
        ApprovedSql.validate(sql);
        return executeRows(runId,sql,"paid_orders",args);
    }
    private Semantic.Compiled plan(LocalDate start,LocalDate end) {
        return semantic.plan(new Semantic.Query(List.of("GMV","ORDERS","UV"),List.of("stat_date","category"),start,end,null,500));
    }
    public String generatedSql() {
        return semantic==null?null:plan(LocalDate.of(2000,1,1),LocalDate.of(2000,1,1)).sql();
    }
    private List<Daily> executeSemantic(String runId,LocalDate start,LocalDate end) {
        Semantic.Compiled compiled=plan(start,end);
        new SemanticSqlCompiler().validateGenerated(compiled.sql(),plan(start,end).sql());
        return executeRows(runId,compiled.sql(),"orders",compiled.parameters().toArray());
    }
    private List<Daily> executeRows(String runId,String sql,String ordersColumn,Object... args) {
        long start = System.nanoTime();
        List<Daily> rows;
        try {
            rows = reader.query(sql, (rs,n) -> {
                long uv = rs.getLong("uv");
                if (rs.wasNull()) throw new BusinessException("DATA_COVERAGE_INCOMPLETE");
                return new Daily(rs.getDate("stat_date").toLocalDate(), rs.getString("category"),
                    rs.getBigDecimal("gmv"), rs.getLong(ordersColumn), uv);
            }, args);
            if (rows.size() > ApprovedSql.MAX_RESULT_ROWS)
                throw new BusinessException("QUERY_RESULT_LIMIT_EXCEEDED");
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
