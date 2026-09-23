package com.ecom.agent;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.ecom.agent.collaboration.*;
import com.ecom.domain.*;
import com.ecom.domain.Analysis.*;
import com.ecom.domain.ModelRuntime.*;
import com.ecom.domain.Ports.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static com.ecom.agent.collaboration.AgentContracts.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** StateGraph coordinates intent/supervision/verification; each expert owns its own model loop. */
public final class MultiAgentWorkflow {
    private final Gateway gateway;private final Sessions sessions;private final RunStore store;private final ObjectMapper json;
    private final Supervisor supervisor;
    public MultiAgentWorkflow(Gateway gateway,Sessions sessions,Knowledge knowledge,AnalyticsReader reader,RunStore store,ObjectMapper json) {
        this.gateway=gateway;this.sessions=sessions;this.store=store;this.json=json;this.supervisor=new Supervisor(new QueryAgent(knowledge,reader));
    }
    private static final class State {Decision decision;ExpertResult result;Report report;}
    public void execute(Run run) throws Exception {
        ModelRunGuard.acquire(run.owner());
        try {
            var rt=new AgentRuntime(run,store,gateway,sessions,json);var state=new State();
            StateGraph graph=new StateGraph("supervised-experts",()->Map.of("stage",new ReplaceStrategy()));
            graph.addNode("intent",node_async(ignored->{rt.check();state.decision=new IntentAgent().decide(rt);validateRoute(run.request(),state.decision);return Map.of("stage","intent");}));
            graph.addNode("supervisor",node_async(ignored->{rt.check();state.result=supervisor.dispatch(rt,state.decision);return Map.of("stage","supervisor");}));
            graph.addNode("critic",node_async(ignored->{rt.check();validate(state.decision,state.result);rt.event("critic","SUCCEEDED","专家输出与原任务意图、证据和金额一致；模型解释仍需人工核对");return Map.of("stage","critic");}));
            graph.addNode("report",node_async(ignored->{
                rt.check();ExpertResult r=state.result;
                String conclusion=r.data()==null?(r.goal()==Goal.DEFINITION?"GMV：已支付订单金额，当前口径不扣退款。":r.goal()==Goal.OPS?"已读取当前账号的任务状态；未执行运维写操作。":"已说明系统能力与当前边界；未查询业务数据。"):
                    "目标日 GMV 为 "+r.data().current().gmv()+" 元。";
                if(r.data()!=null && r.data().previous()!=null) conclusion+=" 对比日 GMV 为 "+r.data().previous().gmv()+" 元；差额 "+r.data().current().gmv().subtract(r.data().previous().gmv())+" 元。";
                state.report=new Report("多 Agent 协作 · "+r.goal(),conclusion,"合成数据；专家独立上下文和工具权限已生效。GMV 诊断树是受限的 UV/CVR/AOV 数值贡献递归，不是业务因果，也不支持任意维度或公式。",r.data(),r.evidence(),String.join("\n\n",r.sql()),ModelRuntime.MULTI_MODE,rt.redact(r.answer()));
                Snapshot snapshot=new Snapshot();snapshot.report=state.report;snapshot.result=r.data();snapshot.evidence=r.evidence();
                store.checkpoint(run.id(),snapshot);rt.event("report","SUCCEEDED","多 Agent 协作完成；交接事件已保存，模型对话和密钥不持久化");return Map.of("stage","report");
            }));
            graph.addEdge(StateGraph.START,"intent").addEdge("intent","supervisor").addEdge("supervisor","critic").addEdge("critic","report").addEdge("report",StateGraph.END);
            graph.compile().invoke(Map.of("stage","created"));store.succeed(run.id(),state.report);
        } finally {ModelRunGuard.release(run.owner());}
    }
    private static void validateRoute(Request request,Decision decision) {
        String q=request.question().toLowerCase(Locale.ROOT);
        boolean metric=q.contains("gmv") || q.contains("成交额");
        boolean comparison=List.of("对比","比较","下降","上涨","环比","同比","为什么","归因").stream().anyMatch(q::contains);
        if(metric && comparison && !Set.of(Goal.COMPARE,Goal.ATTRIBUTION,Goal.TREND).contains(decision.goal()))
            throw new BusinessException("INTENT_ROUTE_CONFLICT");
        if(metric && q.contains("多少") && Set.of(Goal.DEFINITION,Goal.OPS,Goal.OTHER).contains(decision.goal()))
            throw new BusinessException("INTENT_ROUTE_CONFLICT");
    }
    private static void validate(Decision decision,ExpertResult r) {
        if(r==null || r.goal()!=decision.goal()) throw new BusinessException("HANDOFF_INVALID");
        if(r.goal()==Goal.OPS || r.goal()==Goal.OTHER) {
            if(r.data()!=null || !r.sql().isEmpty()) throw new BusinessException("HANDOFF_INVALID");return;
        }
        if(r.evidence().isEmpty() || (r.goal()!=Goal.DEFINITION && r.data()==null)) throw new BusinessException("MODEL_EVIDENCE_REQUIRED");
        if((r.goal()==Goal.COMPARE || r.goal()==Goal.ATTRIBUTION) && r.data().previous()==null) throw new BusinessException("HANDOFF_EVIDENCE_REQUIRED");
        if(r.data()!=null && r.data().previous()!=null) {
            BigDecimal sum=r.data().contributions().stream().map(Contribution::delta).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(sum.compareTo(r.data().current().gmv().subtract(r.data().previous().gmv()))!=0) throw new BusinessException("CONTRIBUTION_MISMATCH");
        }
    }
}
