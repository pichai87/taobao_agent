package com.ecom.agent.collaboration;

import com.ecom.domain.BusinessException;
import com.ecom.domain.ModelRuntime.*;
import java.util.*;
import static com.ecom.agent.collaboration.AgentContracts.*;

/** Two separately scoped experts; neither can mutate services or inspect another owner's tasks. */
public final class SupportAgent {
    private final boolean ops;
    public SupportAgent(boolean ops) {this.ops=ops;}
    public String actor() {return ops?"ops_agent":"other_agent";}
    public List<Tool> tools() {return List.of(AgentRuntime.emptyTool(ops?"inspect_my_runs":"describe_capabilities",
        ops?"Read the current owner's recent task statuses. No service mutation or cloud ops.":"Read supported features and explicitly unsupported capabilities."));}
    public ExpertResult respond(AgentRuntime rt) throws Exception {
        var messages=rt.context(actor(),ops?"You are the read-only operations expert. Inspect current owner's tasks, explain status, and never claim to restart, delete or change anything.":
            "You are the fallback expert. Describe supported capabilities, greet or decline unsupported tasks. Do not invent GMV numbers, business causes or external access.",Map.of("question",rt.run.request().question()));
        boolean observed=false;
        for(int round=0;round<3;round++) {
            Turn turn=rt.complete(actor(),messages,tools());
            if(turn.calls().isEmpty()) {
                if(observed && !turn.text().isBlank()) return new ExpertResult(ops?Goal.OPS:Goal.OTHER,null,List.of(),List.of(),rt.redact(turn.text()));
                messages.add(Message.text("system","Read your permitted information tool before finishing."));continue;
            }
            for(Call call:turn.calls()) {
                rt.authorize(actor(),call,tools(),messages);AgentRuntime.empty(call);
                Object result=ops?rt.store.list(rt.run.owner()).stream().limit(5).map(r->Map.of("id",r.id(),"status",r.status().name(),"mode",r.mode(),"errorCode",r.errorCode()==null?"":r.errorCode())).toList():
                    Map.of("supported",List.of("GMV 日查询","两日对比和品类贡献","七日趋势","品类排序","指标口径","自己的任务状态"),
                        "notSupported",List.of("任意 SQL","真实淘宝内部数据","递归业务归因","生产调度运维写操作"));
                observed=true;rt.result(actor(),messages,call,result);
            }
        }
        throw new BusinessException("MODEL_EVIDENCE_REQUIRED");
    }
}
