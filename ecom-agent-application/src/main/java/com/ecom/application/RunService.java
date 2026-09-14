package com.ecom.application;

import com.ecom.agent.AnalysisWorkflow;
import com.ecom.domain.*;
import com.ecom.domain.Ports.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.ecom.domain.Analysis.*;

/** Application（应用层）：鉴权后的用例编排；幂等与状态竞争由数据库约束保障。 */
public class RunService implements RunUseCases {
    private static final Logger log=LoggerFactory.getLogger(RunService.class);
    private final RunStore store;
    private final Planner planner;
    private final AnalysisWorkflow workflow;
    private final Executor executor;
    public RunService(RunStore store,Planner planner,AnalysisWorkflow workflow,Executor executor) {
        this.store=store; this.planner=planner; this.workflow=workflow; this.executor=executor;
        store.recoverInterrupted();
    }
    public Run submit(String owner,String key,Request q) {
        if(key==null || !key.matches("[a-zA-Z0-9_-]{8,128}")) throw new BusinessException("INVALID_IDEMPOTENCY_KEY");
        if(q==null || q.question()==null || q.question().isBlank() || q.question().length()>2000 || q.date()==null)
            throw new BusinessException("INVALID_REQUEST");
        if(!"GMV".equals(q.metric())) throw new BusinessException("METRIC_NOT_SUPPORTED");
        // 日期字段是执行依据；不让模型凭空补日期。
        if(q.compareDate()==null) q=new Request(q.question(),q.date(),q.date().minusDays(1),q.metric(),q.reviewRequired());
        Run run=store.create(owner,key,q,planner.mode());
        if(run.status()==Status.QUEUED) dispatch(run);
        return get(owner,run.id());
    }
    private void dispatch(Run run) {
        if(!store.transition(run.id(),Status.QUEUED,Status.RUNNING)) return;
        try {
            executor.execute(()-> {
                try {workflow.execute(run);}
                catch(Exception e) {
                    String code="EXECUTION_FAILED";
                    Throwable cause=e;
                    while(cause!=null) {
                        if(cause instanceof BusinessException b) {code=b.code();break;}
                        cause=cause.getCause();
                    }
                    store.fail(run.id(),code);
                    store.event(run.id(),"run","STOPPED",code,0);
                    log.warn("runId={} code={}",run.id(),code,e);
                }
            });
        } catch(RejectedExecutionException e) {store.fail(run.id(),"CAPACITY_EXCEEDED");}
    }
    public Run get(String owner,String id) {return store.find(id,owner).orElseThrow(()->new BusinessException("RUN_NOT_FOUND"));}
    public List<Run> list(String owner) {return store.list(owner);}
    public List<Event> events(String owner,String id,long after) {get(owner,id);return store.events(id,after);}
    public Run approve(String owner,String id) {
        Run run=get(owner,id);
        if(!store.transition(id,Status.WAITING_FOR_REVIEW,Status.QUEUED)) throw new BusinessException("INVALID_RUN_STATE");
        store.event(id,"review","APPROVED","任务所有者确认执行",0);
        dispatch(run);
        return get(owner,id);
    }
    public Run resume(String owner,String id) {
        Run run=get(owner,id);
        if(!run.mode().equals(planner.mode())) throw new BusinessException("MODEL_MODE_CHANGED");
        if(!store.transition(id,Status.INTERRUPTED,Status.QUEUED)) throw new BusinessException("INVALID_RUN_STATE");
        dispatch(run);
        return get(owner,id);
    }
    public Run cancel(String owner,String id) {
        Run run=get(owner,id);
        if(!Set.of(Status.WAITING_FOR_REVIEW,Status.QUEUED,Status.RUNNING,Status.INTERRUPTED).contains(run.status())
            || !store.transition(id,run.status(),Status.CANCELLED)) throw new BusinessException("INVALID_RUN_STATE");
        store.event(id,"run","CANCELLED","任务所有者取消",0);
        return get(owner,id);
    }
}
