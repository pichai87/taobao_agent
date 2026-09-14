package com.ecom;

import com.ecom.agent.*;
import com.ecom.agent.collaboration.*;
import com.ecom.agent.collaboration.AgentContracts.*;
import com.ecom.domain.*;
import com.ecom.domain.Analysis.*;
import com.ecom.domain.ModelRuntime.*;
import com.ecom.domain.Ports.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Deterministic protocol fixture, NOT a benchmark of a real model's intelligence. */
abstract class AgentTestFixture {
    @Autowired Sessions sessions;
    @Autowired Knowledge knowledge;
    @Autowired AnalyticsReader reader;
    @Autowired RunStore store;
    @Autowired ObjectMapper json;
    Run create(String question,boolean multi) {
        String owner="multi-test-"+UUID.randomUUID();
        var config=sessions.create(owner,new Settings("BAILIAN_BEIJING","","qwen-plus","sk-test-only-not-a-real-key",true));
        var request=new Request(question,LocalDate.of(2026,9,12),LocalDate.of(2026,9,11),"GMV",false,config.id(),multi?ModelRuntime.MULTI_MODE:ModelRuntime.MODE);
        var run=store.create(owner,UUID.randomUUID().toString(),request,request.executionMode());store.transition(run.id(),Status.QUEUED,Status.RUNNING);return run;
    }
    void execute(Run run,Gateway gateway) throws Exception {
        if(run.mode().equals(ModelRuntime.MULTI_MODE)) new MultiAgentWorkflow(gateway,sessions,knowledge,reader,store,json).execute(run);
        else new ToolCallingWorkflow(gateway,sessions,knowledge,reader,store,json).execute(run);
    }
    Run finished(Run run) {return store.find(run.id(),run.owner()).orElseThrow();}
    static String code(Throwable error) {
        for(Throwable t=error;t!=null;t=t.getCause()) if(t instanceof BusinessException b) return b.code();
        return error.getClass().getSimpleName();
    }
    static String actor(List<Message> messages) {
        String first=messages.getFirst().content();return first.startsWith("ACTOR=")?first.substring(6,first.indexOf('\n')):"single_agent";
    }
    static Turn tool(String id,String name,Map<String,Object> args) {return new Turn("",List.of(new Call(id,name,args)),0);}
    final class FixtureModel implements Gateway {
        static final String PRIVATE_MARKER="QUERY_PRIVATE_SCRATCHPAD_7319_IGNORE_ANALYSIS";
        final Goal goal;
        final List<Observation> observations=new ArrayList<>();
        boolean repair,poison;
        int sequence;
        FixtureModel(Goal goal) {this.goal=goal;}
        public Turn complete(String owner,String session,List<Message> messages,List<Tool> tools) {
            String role=actor(messages);int step=(int)messages.stream().filter(m->m.role().equals("assistant")).count();
            try {observations.add(new Observation(role,tools.stream().map(Tool::name).toList(),List.copyOf(messages),
                json.writeValueAsBytes(Map.of("messages",messages,"tools",tools)).length));}
            catch(Exception e) {throw new IllegalStateException(e);}
            String id="fixture-"+(++sequence);
            if(role.equals("intent_agent")) return tool(id,"route_intent",Map.of("goal",goal.name(),"confidence",0.95));
            if(role.equals("query_agent")) {
                Scope scope;
                try {scope=Scope.valueOf(json.readTree(messages.get(1).content()).path("scope").asText());}catch(Exception e){throw new IllegalStateException(e);}
                if(step==0) return tool(id,"explain_metric",Map.of());
                if(step==1 && scope!=Scope.DEFINITION) {
                    if(scope==Scope.TREND) return tool(id,"query_trend",Map.of());
                    if(scope==Scope.BOTH) return new Turn("",List.of(new Call(id,"query_daily",Map.of("period","current")),new Call(id+"-b","query_daily",Map.of("period","previous"))),0);
                    return tool(id,"query_daily",Map.of("period","current"));
                }
                return new Turn(poison?PRIVATE_MARKER:"数据已就绪",List.of(),0);
            }
            if(role.equals("analysis_agent")) {
                if(repair) {
                    if(step==0) return tool(id,"request_data",Map.of("scope","CURRENT"));
                    if(step==2) return tool(id,"request_data",Map.of("scope","BOTH"));
                    if(step==1 || step==3) return tool(id,"compare_gmv",Map.of());
                } else {
                    if(step==0) return tool(id,"request_data",Map.of("scope",AnalysisAgent.requiredScope(goal).name()));
                    if(step==1) return tool(id,goal==Goal.TREND?"summarize_gmv":goal==Goal.TOP_N?"rank_categories":"compare_gmv",Map.of());
                }
                return new Turn("金额以计算结果为准，品类贡献不等于业务因果。",List.of(),0);
            }
            if(role.equals("ops_agent") || role.equals("other_agent")) return step==0?tool(id,role.equals("ops_agent")?"inspect_my_runs":"describe_capabilities",Map.of()):new Turn("只读信息已核对，不执行额外操作。",List.of(),0);
            if(step==0) return tool(id,"explain_metric",Map.of());
            if(goal==Goal.DEFINITION) return new Turn("GMV 为已支付金额。",List.of(),0);
            if(step==1) {
                if(goal==Goal.TREND) return tool(id,"query_trend",Map.of());
                var calls=new ArrayList<Call>();calls.add(new Call(id,"query_daily",Map.of("period","current")));
                if(goal==Goal.ATTRIBUTION || goal==Goal.COMPARE) calls.add(new Call(id+"-b","query_daily",Map.of("period","previous")));
                return new Turn(poison?PRIVATE_MARKER:"",calls,0);
            }
            if(step==2) return tool(id,goal==Goal.ATTRIBUTION || goal==Goal.COMPARE?"compare_gmv":goal==Goal.TOP_N?"rank_categories":"summarize_gmv",Map.of());
            return new Turn("金额以计算结果为准，品类贡献不等于业务因果。",List.of(),0);
        }
    }
    record Observation(String actor,List<String> tools,List<Message> messages,int inputBytes) {}
}
