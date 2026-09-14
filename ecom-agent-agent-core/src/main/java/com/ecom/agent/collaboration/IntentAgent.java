package com.ecom.agent.collaboration;

import com.ecom.domain.*;
import com.ecom.domain.ModelRuntime.*;
import com.ecom.knowledge.RulePlanner;
import java.util.*;
import static com.ecom.agent.collaboration.AgentContracts.*;

/** A single routing-only model call. No database or calculation tools are exposed. */
public final class IntentAgent {
    public static final String ACTOR="intent_agent";
    public static List<Tool> tools() {
        return List.of(new Tool("route_intent","Classify the primary request; dates are already bound by the form.",Map.of(
            "type","object","properties",Map.of("goal",Map.of("type","string","enum",Arrays.stream(Goal.values()).map(Enum::name).toList()),
                "confidence",Map.of("type","number","minimum",0,"maximum",1)),"required",List.of("goal","confidence"),"additionalProperties",false)));
    }
    public Decision decide(AgentRuntime rt) throws Exception {
        var messages=rt.context(ACTOR,"You only classify intent. Call route_intent exactly once. QUERY: target day total; COMPARE: two dates; ATTRIBUTION: why changed; TREND: 7 days; TOP_N: category rank; DEFINITION: meaning; OPS: inspect own task status; OTHER: unsupported requests or greeting. Do not execute the request.",
            Map.of("question",rt.run.request().question(),"date",rt.run.request().date(),"compareDate",rt.run.request().compareDate()));
        Turn turn;
        try {turn=rt.complete(ACTOR,messages,tools());}
        catch(BusinessException ex) {
            if(!ex.code().equals("MODEL_RESPONSE_INVALID")) throw ex;
            return fallback(rt);
        }
        if(turn.calls().size()!=1) return fallback(rt);
        Call call=turn.calls().getFirst();rt.authorize(ACTOR,call,tools(),messages);
        try {
            if(!call.arguments().keySet().equals(Set.of("goal","confidence")) || !(call.arguments().get("confidence") instanceof Number n)) return fallback(rt);
            Goal goal=Goal.valueOf((String)call.arguments().get("goal"));double confidence=n.doubleValue();
            if(!Double.isFinite(confidence) || confidence<0.7 || confidence>1) return fallback(rt);
            rt.event(ACTOR,"ROUTED",goal+"；置信度="+confidence+"；日期使用表单值");
            return new Decision(goal,confidence,"MODEL");
        } catch(IllegalArgumentException | ClassCastException | NullPointerException invalid) {return fallback(rt);}
    }
    private Decision fallback(AgentRuntime rt) {
        Goal goal;
        try {goal=Goal.valueOf(new RulePlanner().plan(rt.run.request()).intent().name());}
        catch(BusinessException unsupported) {goal=rt.run.request().question().matches(".*(任务状态|服务状态|失败任务).*")?Goal.OPS:Goal.OTHER;}
        rt.event(ACTOR,"FALLBACK","意图结果无效或低置信度，显式使用规则路由到 "+goal+"；不伪称模型成功分类");
        return new Decision(goal,0,"RULE_FALLBACK");
    }
}
