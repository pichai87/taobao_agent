package com.ecom;

import com.ecom.agent.*;
import com.ecom.agent.collaboration.*;
import com.ecom.agent.collaboration.AgentContracts.*;
import com.ecom.domain.*;
import com.ecom.domain.Analysis.*;
import com.ecom.domain.ModelRuntime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class MultiAgentIntegrationTest extends AgentTestFixture {
    @ParameterizedTest @EnumSource(Goal.class)
    void routesToIndependentExpertsAndProducesExpectedData(Goal goal) throws Exception {
        Run run=create(goal==Goal.DEFINITION?"GMV 是什么":"fixture "+goal,true);FixtureModel model=new FixtureModel(goal);execute(run,model);
        assertThat(finished(run).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(finished(run).report().mode()).isEqualTo(ModelRuntime.MULTI_MODE);
        if(Set.of(Goal.OPS,Goal.OTHER,Goal.DEFINITION).contains(goal)) assertThat(finished(run).report().data()).isNull();
        else assertThat(finished(run).report().data().current().gmv()).isEqualByComparingTo("2600");
        if(goal==Goal.ATTRIBUTION || goal==Goal.COMPARE) assertThat(finished(run).report().data().previous().gmv()).isEqualByComparingTo("3000");
        if(goal==Goal.TREND) assertThat(finished(run).report().data().rows()).hasSize(21);
        if(goal==Goal.TOP_N) assertThat(finished(run).report().data().rows().getFirst().gmv()).isEqualByComparingTo("1000");
        if(goal==Goal.OPS || goal==Goal.OTHER) assertThat(model.observations).noneMatch(o->o.actor().equals("query_agent") || o.actor().equals("analysis_agent"));
        assertThat(store.events(run.id(),0)).anyMatch(e->e.node().equals("handoff") && e.status().equals("SUCCEEDED"));
        assertThat(model.observations).allMatch(o->o.tools().size()<=2);
    }
    @Test void analystsCannotDirectlyQueryEvenThoughSingleAgentCan() {
        Run run=create("GMV 为什么下降",true);FixtureModel normal=new FixtureModel(Goal.ATTRIBUTION);
        Throwable error=catchThrowable(()->execute(run,(o,s,m,t)->actor(m).equals("analysis_agent")?tool("rogue","query_daily",Map.of("period","current")):normal.complete(o,s,m,t)));
        assertThat(code(error)).isEqualTo("AGENT_TOOL_FORBIDDEN");
        assertThat(store.events(run.id(),0)).noneMatch(e->e.node().equals("query"));
        assertThat(ToolCallingWorkflow.tools()).anyMatch(t->t.name().equals("query_daily"));
        assertThat(finished(run).report()).isNull();
    }
    @Test void queryExpertCannotCalculateOrChangeRequestedDates() {
        for(boolean forbidden:new boolean[]{true,false}) {
            Run run=create("GMV",true);FixtureModel normal=new FixtureModel(Goal.QUERY);AtomicInteger counter=new AtomicInteger();
            Throwable error=catchThrowable(()->execute(run,(o,s,m,t)-> {
                if(!actor(m).equals("query_agent")) return normal.complete(o,s,m,t);
                return forbidden?tool("bad","compare_gmv",Map.of()):tool("bad"+counter.incrementAndGet(),"query_daily",Map.of("period","current","date","2000-01-01"));
            }));
            assertThat(code(error)).isEqualTo(forbidden?"AGENT_TOOL_FORBIDDEN":"INVALID_TOOL_ARGUMENTS");
            assertThat(store.events(run.id(),0)).noneMatch(e->e.node().equals("query"));
        }
    }
    @Test void missingComparisonDataTriggersNewHandoffAndRecalculation() throws Exception {
        Run run=create("GMV 为什么下降",true);FixtureModel model=new FixtureModel(Goal.ATTRIBUTION);model.repair=true;execute(run,model);
        assertThat(finished(run).report().data().previous().gmv()).isEqualByComparingTo("3000");
        assertThat(store.events(run.id(),0)).anyMatch(e->e.node().equals("analysis_agent") && e.detail().equals("TOOL_PREREQUISITE"));
        assertThat(store.events(run.id(),0).stream().filter(e->e.node().equals("handoff") && e.status().equals("STARTED"))).hasSize(3);
    }
    @Test void rawQueryConversationIsNotPassedToAnalysis() throws Exception {
        Run run=create("GMV 为什么下降",true);FixtureModel model=new FixtureModel(Goal.ATTRIBUTION);model.poison=true;execute(run,model);
        assertThat(model.observations.stream().filter(o->o.actor().equals("analysis_agent")).flatMap(o->o.messages().stream()).map(Message::content))
            .noneMatch(s->s.contains(FixtureModel.PRIVATE_MARKER));
        assertThat(model.observations.stream().filter(o->o.actor().equals("analysis_agent")).flatMap(o->o.messages().stream()).map(Message::content))
            .anyMatch(s->s.contains("2600"));
        assertThat(finished(run).report().modelAnswer()).doesNotContain(FixtureModel.PRIVATE_MARKER);
    }
    @Test void rejectsWrongRunDatesAndIncompleteEvidencePackets() {
        Run run=create("GMV 为什么下降",true);var rt=new AgentRuntime(run,store,(o,s,m,t)->new Turn("",List.of(),0),sessions,json);
        var evidence=knowledge.recall("GMV","GMV");var rows=reader.query(run.id(),run.request().date());
        List<EvidencePacket> invalid=List.of(
            new EvidencePacket("x","other-run",run.request().date(),run.request().compareDate(),Scope.CURRENT,rows,List.of(),evidence,List.of()),
            new EvidencePacket("x",run.id(),run.request().date().minusDays(3),run.request().compareDate(),Scope.CURRENT,rows,List.of(),evidence,List.of()),
            new EvidencePacket("x",run.id(),run.request().date(),run.request().compareDate(),Scope.CURRENT,rows,List.of(),List.of(),List.of()));
        for(var packet:invalid) assertThatThrownBy(()->QueryAgent.validate(rt,new DataRequest(Scope.CURRENT),packet)).hasMessage("HANDOFF_INVALID");
        var incomplete=new EvidencePacket("x",run.id(),run.request().date(),run.request().compareDate(),Scope.BOTH,rows,List.of(),evidence,List.of());
        assertThatThrownBy(()->QueryAgent.validate(rt,new DataRequest(Scope.BOTH),incomplete)).hasMessage("HANDOFF_EVIDENCE_REQUIRED");
    }
    @Test void lowConfidenceUsesExplicitRuleFallbackButAuthErrorsDoNot() throws Exception {
        Run run=create("GMV 为什么下降",true);FixtureModel normal=new FixtureModel(Goal.ATTRIBUTION);
        execute(run,(o,s,m,t)->actor(m).equals("intent_agent")?tool("low","route_intent",Map.of("goal","OTHER","confidence",0.1)):normal.complete(o,s,m,t));
        assertThat(store.events(run.id(),0)).anyMatch(e->e.status().equals("FALLBACK"));
        Run auth=create("GMV",true);
        assertThat(code(catchThrowable(()->execute(auth,(o,s,m,t)->{throw new BusinessException("MODEL_AUTH_FAILED");})))).isEqualTo("MODEL_AUTH_FAILED");
        assertThat(store.events(auth.id(),0)).noneMatch(e->e.node().equals("query"));
    }
    @Test void cancelOrClearDuringHandoffPreventsDatabaseAccess() {
        for(boolean cancel:new boolean[]{true,false}) {
            Run run=create("GMV 为什么下降",true);FixtureModel normal=new FixtureModel(Goal.ATTRIBUTION);
            Throwable error=catchThrowable(()->execute(run,(o,s,m,t)->{
                if(actor(m).equals("query_agent")) {if(cancel) store.transition(run.id(),Status.RUNNING,Status.CANCELLED);else sessions.clear(o,s);}
                return normal.complete(o,s,m,t);
            }));
            assertThat(code(error)).isEqualTo(cancel?"RUN_STOPPED":"MODEL_SESSION_EXPIRED");
            assertThat(store.events(run.id(),0)).noneMatch(e->e.node().equals("query"));
        }
    }
    @Test void allExpertsShareOneGlobalBudget() {
        Run run=create("GMV",true);var rt=new AgentRuntime(run,store,(o,s,m,t)->new Turn("",List.of(),0),sessions,json);
        var messages=new ArrayList<>(List.of(Message.text("system","budget test")));
        for(int i=0;i<16;i++) rt.complete(i%2==0?"query_agent":"analysis_agent",messages,List.of());
        assertThatThrownBy(()->rt.complete("ops_agent",messages,List.of())).hasMessage("MULTI_ROUND_LIMIT");
        for(int i=0;i<6;i++) rt.handoff("Supervisor","QueryAgent","test");
        assertThatThrownBy(()->rt.handoff("Supervisor","QueryAgent","test")).hasMessage("MULTI_HANDOFF_LIMIT");
        var tool=AgentRuntime.emptyTool("test","test");
        for(int i=0;i<24;i++) rt.authorize("query_agent",new Call("id"+i,"test",Map.of()),List.of(tool));
        assertThatThrownBy(()->rt.authorize("query_agent",new Call("overflow","test",Map.of()),List.of(tool))).hasMessage("MULTI_TOOL_BUDGET");
    }
    @Test void modelQuotaIsSharedAcrossSingleAndMultiModes() {
        Run run=create("GMV",true);ModelRunGuard.acquire(run.owner());
        try {assertThatThrownBy(()->execute(run,new FixtureModel(Goal.QUERY))).hasMessage("MODEL_BUSY");}
        finally {ModelRunGuard.release(run.owner());}
    }
    @Test void callIdsAreUniquePerConversationNotAcrossDifferentExperts() {
        Run run=create("GMV",true);var rt=new AgentRuntime(run,store,(o,s,m,t)->new Turn("",List.of(),0),sessions,json);
        var tools=List.of(AgentRuntime.emptyTool("test","test"));var call=new Call("call_0","test",Map.of());
        Object first=new Object(),second=new Object();rt.authorize("query_agent",call,tools,first);rt.authorize("query_agent",call,tools,second);
        assertThatThrownBy(()->rt.authorize("query_agent",call,tools,first)).hasMessage("MODEL_RESPONSE_INVALID");
    }
    @Test void opsExpertOnlySeesItsOwnersRunMetadata() throws Exception {
        Run other=create("private other question",true),own=create("查看任务状态",true);FixtureModel model=new FixtureModel(Goal.OPS);execute(own,model);
        String visible=json.writeValueAsString(model.observations.stream().filter(o->o.actor().equals("ops_agent")).toList());
        assertThat(visible).contains(own.id()).doesNotContain(other.id(),"private other question","sk-test-only");
    }
    @Test void cumulativeReportedTokenBudgetStopsBeforeAnyTool() {
        Run run=create("GMV",true);
        Throwable error=catchThrowable(()->execute(run,(o,s,m,t)->new Turn("",List.of(new Call("id","route_intent",Map.of("goal","QUERY","confidence",0.9))),30001)));
        assertThat(code(error)).isEqualTo("MULTI_TOKEN_BUDGET");
        assertThat(store.events(run.id(),0)).noneMatch(e->e.node().equals("query"));
    }
    @Test void wrongIntentCannotTurnComparisonIntoADataFreeAnswer() {
        Run run=create("GMV 为什么下降",true);
        assertThat(code(catchThrowable(()->execute(run,new FixtureModel(Goal.OTHER))))).isEqualTo("INTENT_ROUTE_CONFLICT");
        assertThat(finished(run).report()).isNull();
        assertThat(store.events(run.id(),0)).noneMatch(e->e.node().equals("other_agent"));
    }
}
