package com.ecom.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static com.ecom.domain.Analysis.*;

/** Port（端口）：业务需要的能力；数据库、模型通过实现接口接入。 */
public final class Ports {
    private Ports() {}
    public interface Planner {
        Plan plan(Request request);
        String mode();
    }
    public interface AnalyticsReader {
        List<Daily> query(String runId, LocalDate date);
        List<Daily> trend(String runId, LocalDate start, LocalDate end);
        /** 供报告展示实际生成的 SQL。测试替身/旧模板实现可不提供。 */
        default String generatedSql() {return null;}
    }
    public interface Knowledge {
        List<Metric> metrics();
        List<Evidence> recall(String question, String metric);
        default List<Evidence> context(String question,String metric) {return recall(question,metric);}
    }
    public interface RunStore {
        Run create(String owner, String key, Request request, String mode);
        Optional<Run> find(String id, String owner);
        List<Run> list(String owner);
        boolean transition(String id, Status from, Status to);
        Snapshot snapshot(String id);
        void checkpoint(String id, Snapshot snapshot);
        void succeed(String id, Report report);
        void fail(String id, String code);
        default void failWithEvent(String id,String code) {fail(id,code);event(id,"run","STOPPED",code,0);}
        default boolean cancelWithEvent(String id,Status from) {
            if(!transition(id,from,Status.CANCELLED)) return false;
            event(id,"run","CANCELLED","任务所有者取消",0);return true;
        }
        void event(String id, String node, String status, String detail, long elapsedMs);
        List<Event> events(String id, long after);
        void recoverInterrupted();
    }
    public interface RunUseCases {
        Run submit(String owner, String key, Request request);
        Run get(String owner, String id);
        List<Run> list(String owner);
        List<Event> events(String owner, String id, long after);
        Run approve(String owner, String id);
        Run resume(String owner, String id);
        Run cancel(String owner, String id);
    }
}
