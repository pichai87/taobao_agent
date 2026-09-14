package com.ecom;

import com.ecom.agent.ToolCallingWorkflow;
import com.ecom.domain.*;
import com.ecom.domain.ModelRuntime.*;
import com.ecom.domain.Ports.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.ecom.domain.Analysis.*;
import static org.assertj.core.api.Assertions.*;

/** 模拟供应商决策，但使用真实数据库/工具/审计；不使用真实密钥、不请求外部网络。 */
@SpringBootTest
@ActiveProfiles("test")
class ToolCallingIntegrationTest {
    @Autowired Sessions sessions;
    @Autowired Knowledge knowledge;
    @Autowired AnalyticsReader reader;
    @Autowired RunStore store;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    private Run run(String question) {
        String owner="tool-test-"+UUID.randomUUID();
        var session=sessions.create(owner,new Settings("BAILIAN_BEIJING","","qwen-plus","sk-test-only-not-a-real-key",true));
        var q=new Request(question,LocalDate.of(2026,9,12),LocalDate.of(2026,9,11),"GMV",false,session.id());
        Run run=store.create(owner,UUID.randomUUID().toString(),q,ModelRuntime.MODE);
        store.transition(run.id(),Status.QUEUED,Status.RUNNING);return run;
    }
    private Turn call(String id,String name,Map<String,Object> args) {return new Turn("",List.of(new Call(id,name,args)),25);}
    private ToolCallingWorkflow workflow(Gateway gateway) {return new ToolCallingWorkflow(gateway,sessions,knowledge,reader,store,json);}
    @Test void modelChoosesToolsReceivesResultsAndProducesVerifiedAmounts() throws Exception {
        Run run=run("GMV 为什么下降");
        var turns=new ArrayDeque<>(List.of(call("a","explain_metric",Map.of()),
            new Turn("",List.of(new Call("b","query_daily",Map.of("period","current")),new Call("c","query_daily",Map.of("period","previous"))),50),
            call("d","compare_gmv",Map.of()),new Turn("根据数据减少了400元。sk-test-only-not-a-real-key",List.of(),50)));
        List<List<Message>> inputs=new ArrayList<>();
        workflow((owner,id,messages,tools)->{inputs.add(messages);return turns.removeFirst();}).execute(run);
        Run done=store.find(run.id(),run.owner()).orElseThrow();
        assertThat(done.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(done.report().data().current().gmv()).isEqualByComparingTo("2600");
        assertThat(done.report().data().previous().gmv()).isEqualByComparingTo("3000");
        assertThat(inputs.getLast()).anyMatch(m->m.role().equals("tool") && m.content().contains("2600"));
        assertThat(done.report().modelAnswer()).contains("[REDACTED]").doesNotContain("sk-test-only");
        assertThat(store.events(run.id(),0)).anyMatch(e->e.node().equals("tool") && e.detail().contains("compare_gmv"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tool_call_audit WHERE run_id=?",Integer.class,run.id())).isEqualTo(2);
        assertThat(db.queryForObject("SELECT snapshot_json FROM agent_run WHERE id=?",String.class,run.id())).doesNotContain("sk-test-only");
    }
    @Test void unknownToolsAreRejectedBeforeDatabaseAccess() {
        Run run=run("GMV");
        assertThatThrownBy(()->workflow((o,i,m,t)->call("x","execute_sql",Map.of("sql","DELETE FROM biz_order"))).execute(run))
            .hasMessage("TOOL_NOT_ALLOWED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tool_call_audit WHERE run_id=?",Integer.class,run.id())).isZero();
    }
    @Test void unauthorizedDateParameterIsNotPassedToQuery() throws Exception {
        Run run=run("GMV");
        var turns=new ArrayDeque<>(List.of(call("a","explain_metric",Map.of()),call("b","query_daily",Map.of("period","current","date","2000-01-01")),
            call("c","query_daily",Map.of("period","current")),call("d","summarize_gmv",Map.of()),new Turn("完成",List.of(),0)));
        workflow((o,i,m,t)->turns.removeFirst()).execute(run);
        assertThat(store.events(run.id(),0)).anyMatch(e->e.status().equals("REJECTED") && e.detail().contains("INVALID_TOOL_ARGUMENTS"));
        assertThat(store.find(run.id(),run.owner()).orElseThrow().report().data().current().gmv()).isEqualByComparingTo("2600");
    }
    @Test void missingEvidenceCannotBePassedOffAsAReport() {
        Run run=run("GMV");
        assertThatThrownBy(()->workflow((o,i,m,t)->new Turn("随便编造 99999 元",List.of(),0)).execute(run)).hasMessage("MODEL_EVIDENCE_REQUIRED");
        assertThat(store.find(run.id(),run.owner()).orElseThrow().report()).isNull();
    }
    @Test void modelRoundLimitStopsRepeatedDecisions() {
        Run run=run("GMV"); AtomicInteger rounds=new AtomicInteger();
        assertThatThrownBy(()->workflow((o,i,m,t)->call("r"+rounds.incrementAndGet(),"explain_metric",Map.of())).execute(run)).hasMessage("MODEL_ROUND_LIMIT");
        assertThat(rounds.get()).isEqualTo(8);
    }
    @Test void metricDefinitionCannotSubstituteForQueriedNumbers() {
        Run run=run("GMV"); AtomicInteger round=new AtomicInteger();
        assertThatThrownBy(()->workflow((o,i,m,t)->round.incrementAndGet()==1?call("definition","explain_metric",Map.of()):
            new Turn("GMV 为 99999 元",List.of(),0)).execute(run)).hasMessage("MODEL_EVIDENCE_REQUIRED");
        assertThat(store.find(run.id(),run.owner()).orElseThrow().report()).isNull();
    }
    @Test void comparisonCannotFinishWithOnlyCurrentPeriod() {
        Run run=run("GMV 为什么下降");
        var turns=new ArrayDeque<>(List.of(call("a","explain_metric",Map.of()),call("b","query_daily",Map.of("period","current")),call("c","summarize_gmv",Map.of())));
        assertThatThrownBy(()->workflow((o,i,m,t)->turns.isEmpty()?new Turn("下降很多",List.of(),0):turns.removeFirst()).execute(run))
            .hasMessage("MODEL_EVIDENCE_REQUIRED");
    }
    @Test void explicitDefinitionQuestionMayFinishWithoutBusinessQueries() throws Exception {
        Run run=run("GMV 是什么？");
        var turns=new ArrayDeque<>(List.of(call("a","explain_metric",Map.of()),new Turn("已支付订单金额",List.of(),0)));
        workflow((o,i,m,t)->turns.removeFirst()).execute(run);
        assertThat(store.find(run.id(),run.owner()).orElseThrow().report().data()).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tool_call_audit WHERE run_id=?",Integer.class,run.id())).isZero();
    }
    @Test void toolAndTokenBudgetsAreEnforced() {
        Run run=run("GMV"); AtomicInteger calls=new AtomicInteger();
        assertThatThrownBy(()->workflow((o,i,m,t)->new Turn("",java.util.stream.IntStream.range(0,4)
            .mapToObj(n->new Call("c"+calls.incrementAndGet(),"explain_metric",Map.<String,Object>of())).toList(),0)).execute(run))
            .hasMessage("MODEL_TOOL_BUDGET");
        Run tokenRun=run("GMV");
        assertThatThrownBy(()->workflow((o,i,m,t)->new Turn("",List.of(),20001)).execute(tokenRun)).hasMessage("MODEL_TOKEN_BUDGET");
    }
    @Test void revokeOrCancelPreventsTheNextToolExecution() {
        Run run=run("GMV");
        assertThatThrownBy(()->workflow((o,i,m,t)->{sessions.clear(o,i);return call("a","query_daily",Map.of("period","current"));}).execute(run))
            .hasMessage("MODEL_SESSION_EXPIRED");
        Run cancelled=run("GMV");
        assertThatThrownBy(()->workflow((o,i,m,t)->{store.transition(cancelled.id(),Status.RUNNING,Status.CANCELLED);return call("a","explain_metric",Map.of());}).execute(cancelled))
            .hasMessage("RUN_STOPPED");
    }
    @Test void duplicateToolCallIdsAreRejected() {
        Run run=run("GMV");
        assertThatThrownBy(()->workflow((o,i,m,t)->call("same","explain_metric",Map.of())).execute(run)).hasMessage("MODEL_RESPONSE_INVALID");
    }
    @Test void noRowsIsARealFailureNotModelGuessing() {
        Run run=run("GMV");
        var emptyReader=new AnalyticsReader() {
            public List<Daily> query(String id,LocalDate date) {return List.of();}
            public List<Daily> trend(String id,LocalDate start,LocalDate end) {return List.of();}
        };
        var turns=new ArrayDeque<>(List.of(call("a","explain_metric",Map.of()),call("b","query_daily",Map.of("period","current"))));
        assertThatThrownBy(()->new ToolCallingWorkflow((o,i,m,t)->turns.removeFirst(),sessions,knowledge,emptyReader,store,json).execute(run)).hasMessage("NO_DATA");
    }
}
