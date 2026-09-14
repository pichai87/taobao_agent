package com.ecom.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 领域契约不依赖 Spring。金额使用 BigDecimal，避免浮点误差。 */
public final class Analysis {
    private Analysis() {}
    public enum Intent { QUERY, COMPARE, ATTRIBUTION, TOP_N, TREND, DEFINITION }
    public enum Status { QUEUED, RUNNING, WAITING_FOR_REVIEW, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
    public record Request(String question, LocalDate date, LocalDate compareDate, String metric,
                          boolean reviewRequired, String modelSessionId, String executionMode) {
        public Request(String question,LocalDate date,LocalDate compareDate,String metric,boolean reviewRequired,String modelSessionId) {
            this(question,date,compareDate,metric,reviewRequired,modelSessionId,null);
        }
        public Request(String question,LocalDate date,LocalDate compareDate,String metric,boolean reviewRequired) {
            this(question,date,compareDate,metric,reviewRequired,null,null);
        }
    }
    public record Plan(Intent intent, List<String> steps, String mode) {}
    public record Evidence(String id, String title, String text, String version) {}
    public record Metric(String code, String name, String definition, String formula, String unit) {}
    public record Daily(LocalDate date, String category, BigDecimal gmv, long orders, long uv) {}
    public record Summary(BigDecimal gmv, long orders, long uv, BigDecimal cvr, BigDecimal aov) {}
    public record Contribution(String dimension, BigDecimal delta) {}
    public record Result(Summary current, Summary previous, BigDecimal changeRate,
                         List<Contribution> contributions, List<Daily> rows) {}
    public record Report(String title, String conclusion, String limitation, Result data,
                         List<Evidence> evidence, String sql, String mode, String modelAnswer) {
        public Report(String title,String conclusion,String limitation,Result data,List<Evidence> evidence,String sql,String mode) {
            this(title,conclusion,limitation,data,evidence,sql,mode,null);
        }
    }
    public record Event(long sequence, String node, String status, String detail, long elapsedMs) {}
    public record Run(String id, String owner, Request request, Status status, Report report,
                      String errorCode, String mode) {}
    /** 保存到 checkpoint 的全部业务状态，支持程序重启后从已完成节点继续。 */
    public static class Snapshot {
        public int completed;
        public Plan plan;
        public List<Evidence> evidence = List.of();
        public List<Daily> rows = List.of();
        public List<Daily> previousRows = List.of();
        public Result result;
        public Report report;
        public Snapshot() {}
    }
}
