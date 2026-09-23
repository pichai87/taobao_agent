package com.ecom.agent.collaboration;

import com.ecom.domain.*;
import com.ecom.domain.Analysis.*;
import com.ecom.domain.ModelRuntime.*;
import com.ecom.tools.AttributionCalculator;
import java.util.*;
import static com.ecom.agent.collaboration.AgentContracts.*;

/** No reader/knowledge dependency: analysis cannot execute a database query directly. */
public final class AnalysisAgent {
    public static final String ACTOR="analysis_agent";
    private final AttributionCalculator calculator=new AttributionCalculator();
    public static Scope requiredScope(Goal goal) {return goal==Goal.TREND?Scope.TREND:goal==Goal.COMPARE || goal==Goal.ATTRIBUTION?Scope.BOTH:Scope.CURRENT;}
    private static String calculation(Goal goal) {return switch(goal) {case COMPARE,ATTRIBUTION -> "compare_gmv";case TOP_N -> "rank_categories";default -> "summarize_gmv";};}
    public static List<Tool> tools(Goal goal) {
        Scope scope=requiredScope(goal);
        return List.of(AgentRuntime.enumTool("request_data","Ask Supervisor to delegate to QueryAgent. Data comes back as a validated evidence packet, not as an expert's prose.","scope",
            scope==Scope.BOTH?List.of("CURRENT","BOTH"):List.of(scope.name())),
            AgentRuntime.emptyTool(calculation(goal),"Calculate with Java using a complete evidence packet. If TOOL_PREREQUISITE is returned, request the full required scope."));
    }
    public ExpertResult analyze(AgentRuntime rt,Goal goal,DataDelegate delegate) throws Exception {
        List<Tool> allowed=tools(goal);
        var messages=rt.context(ACTOR,"You are the analysis expert. You cannot query databases directly. Request a data packet using request_data, then call the calculation tool. Only then finish with an evidence-based explanation. For changes request BOTH periods. Distinguish numeric contribution from business causation. Do not invent promotions or inventory causes. A missing-data error requires a new request_data; do not ask for raw SQL.",
            Map.of("question",rt.run.request().question(),"goal",goal,"requiredScope",requiredScope(goal),"date",rt.run.request().date(),"compareDate",rt.run.request().compareDate()));
        EvidencePacket packet=null;Result result=null;int corrections=0;
        for(int round=0;round<7;round++) {
            Turn turn=rt.complete(ACTOR,messages,allowed);
            if(turn.calls().isEmpty()) {
                if(result!=null && packet!=null && !turn.text().isBlank()) return new ExpertResult(goal,result,packet.evidence(),packet.sql(),rt.redact(turn.text()));
                if(++corrections>2) throw new BusinessException("MODEL_EVIDENCE_REQUIRED");
                messages.add(Message.text("system","Cannot finish without a complete data packet and calculation result. Use request_data then the calculation tool."));continue;
            }
            for(Call call:turn.calls()) {
                rt.authorize(ACTOR,call,allowed,messages);
                try {
                    if(call.name().equals("request_data")) {
                        String scope=AgentRuntime.choice(call,"scope",requiredScope(goal)==Scope.BOTH?List.of("CURRENT","BOTH"):List.of(requiredScope(goal).name()));
                        DataRequest request=new DataRequest(Scope.valueOf(scope));
                        packet=delegate.request(request);QueryAgent.validate(rt,request,packet);result=null;
                        rt.result(ACTOR,messages,call,packet);
                    } else {
                        AgentRuntime.empty(call);
                        if(packet==null || packet.scope()!=requiredScope(goal)) throw new BusinessException("TOOL_PREREQUISITE");
                        QueryAgent.validate(rt,new DataRequest(requiredScope(goal)),packet);
                        List<Daily> current=packet.rows().stream().filter(r->r.date().equals(rt.run.request().date())).toList();
                        result=calculator.calculate(current,packet.previous());
                        if(goal==Goal.TREND) result=new Result(result.current(),null,null,List.of(),packet.rows());
                        if(goal==Goal.TOP_N) result=new Result(result.current(),null,null,List.of(),com.ecom.tools.QuestionConstraints.limit(rt.run.request().question(),current.stream().sorted(Comparator.comparing(Daily::gmv).reversed()).toList()));
                        rt.event("calculate","SUCCEEDED",ACTOR+" 使用数据包 "+packet.id()+" 执行 "+call.name());rt.result(ACTOR,messages,call,result);
                    }
                } catch(BusinessException error) {
                    if(!Set.of("INVALID_TOOL_ARGUMENTS","TOOL_PREREQUISITE").contains(error.code()) || ++corrections>2) throw error;
                    rt.event(ACTOR,"REJECTED",error.code());rt.result(ACTOR,messages,call,Map.of("error",error.code(),"requiredScope",requiredScope(goal)));
                }
            }
        }
        throw new BusinessException("AGENT_ROUND_LIMIT");
    }
}
