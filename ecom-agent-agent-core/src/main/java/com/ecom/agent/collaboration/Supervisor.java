package com.ecom.agent.collaboration;

import com.ecom.domain.Analysis.*;
import com.ecom.tools.AttributionCalculator;
import java.util.*;
import static com.ecom.agent.collaboration.AgentContracts.*;

/** Explicit orchestration, not a second unconstrained LLM: routes contracts and validates returns. */
public final class Supervisor {
    private final QueryAgent query;
    private final AnalysisAgent analysis=new AnalysisAgent();
    private final SupportAgent ops=new SupportAgent(true),other=new SupportAgent(false);
    public Supervisor(QueryAgent query) {this.query=query;}
    public ExpertResult dispatch(AgentRuntime rt,Decision decision) throws Exception {
        Goal goal=decision.goal();
        rt.event("supervisor","ROUTED","意图 "+goal+"；来源 "+decision.source()+"；为专家创建独立上下文与工具权限");
        switch(goal) {
            case DEFINITION,QUERY -> {
                Scope scope=goal==Goal.DEFINITION?Scope.DEFINITION:Scope.CURRENT;
                rt.handoff("Supervisor","QueryAgent",scope.name());var packet=query.collect(rt,new DataRequest(scope));
                QueryAgent.validate(rt,new DataRequest(scope),packet);rt.returned("QueryAgent","Supervisor","数据包 "+packet.id());
                Result data=goal==Goal.DEFINITION?null:new AttributionCalculator().calculate(packet.rows(),List.of());
                return new ExpertResult(goal,data,packet.evidence(),packet.sql(),goal==Goal.DEFINITION?"GMV 为已支付订单金额，当前口径不扣退款。":"查询专家已返回数据，汇总金额由 Java 计算。");
            }
            case OPS,OTHER -> {
                SupportAgent expert=goal==Goal.OPS?ops:other;
                rt.handoff("Supervisor",expert.actor(),"只读能力范围内处理");var result=expert.respond(rt);rt.returned(expert.actor(),"Supervisor","受控工具结果已用于解读");return result;
            }
            default -> {
                rt.handoff("Supervisor","AnalysisAgent",goal.name());
                var packets=new EnumMap<Scope,EvidencePacket>(Scope.class);
                ExpertResult result=analysis.analyze(rt,goal,request->{
                    rt.check();rt.event("supervisor","DATA_REQUEST","AnalysisAgent 申请 "+request.scope()+"；校验范围后分派 QueryAgent");
                    if(packets.containsKey(request.scope())) {
                        rt.event("supervisor","CACHE_HIT","复用本任务同范围的已校验数据包，不重复查询");return packets.get(request.scope());
                    }
                    rt.handoff("AnalysisAgent / Supervisor","QueryAgent",request.scope().name());
                    EvidencePacket packet=query.collect(rt,request);QueryAgent.validate(rt,request,packet);packets.put(request.scope(),packet);
                    rt.returned("QueryAgent / Supervisor","AnalysisAgent","数据包 "+packet.id()+"；仅转交结构化数据和口径，不转交查询专家对话");return packet;
                });
                rt.returned("AnalysisAgent","Supervisor","结构化计算结果与解读，待统一校验");return result;
            }
        }
    }
}
