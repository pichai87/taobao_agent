package com.ecom.agent;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.ecom.domain.*;
import com.ecom.domain.Ports.*;
import com.ecom.knowledge.SkillRegistry;
import com.ecom.tools.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.ecom.domain.Analysis.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** Workflow（工作流）：由真正的 StateGraph 调度，业务检查点另行持久化。 */
public class AnalysisWorkflow {
    private final Planner planner;
    private final Knowledge knowledge;
    private final AnalyticsReader reader;
    private final RunStore store;
    private final AttributionCalculator calculator = new AttributionCalculator();
    public AnalysisWorkflow(Planner planner, Knowledge knowledge, AnalyticsReader reader, RunStore store) {
        this.planner=planner; this.knowledge=knowledge; this.reader=reader; this.store=store;
    }
    public void execute(Run run) throws Exception {
        Snapshot s = store.snapshot(run.id());
        long deadline = System.nanoTime() + 60_000_000_000L;
        StateGraph graph = new StateGraph("ecommerce-analysis",
            () -> Map.of("stage", new ReplaceStrategy()));
        String[] nodes = {"plan", "semantic", "query", "calculate", "critic", "report"};
        for (int i=0; i<nodes.length; i++) {
            final int index=i;
            graph.addNode(nodes[i], node_async(ignored -> {
                // 取消是协作式的：正在执行的 JDBC 最长受查询超时约束。
                if (store.find(run.id(),run.owner()).orElseThrow().status()!=Status.RUNNING)
                    throw new BusinessException("RUN_STOPPED");
                if (System.nanoTime()>deadline) throw new BusinessException("RUN_TIME_BUDGET");
                if (s.completed<=index) {
                    long start=System.nanoTime();
                    store.event(run.id(),nodes[index],"STARTED","节点开始",0);
                    step(index,run,s);
                    s.completed=index+1;
                    store.checkpoint(run.id(),s);
                    store.event(run.id(),nodes[index],"SUCCEEDED","节点完成",(System.nanoTime()-start)/1_000_000);
                }
                return Map.of("stage",nodes[index]);
            }));
            graph.addEdge(i==0 ? StateGraph.START : nodes[i-1], nodes[i]);
        }
        graph.addEdge("report",StateGraph.END);
        graph.compile().invoke(Map.of("stage","created"));
        store.succeed(run.id(),s.report);
    }
    private void step(int index, Run run, Snapshot s) {
        Request q=run.request();
        switch(index) {
            case 0 -> {
                s.plan=planner.plan(q);
                SkillRegistry.Skill skill=new SkillRegistry().select(s.plan.intent());
                store.event(run.id(),"skill","SELECTED",skill.name()+"@"+skill.version(),0);
            }
            case 1 -> s.evidence=knowledge.recall(q.question(),q.metric());
            case 2 -> {
                if (s.plan.intent()==Intent.DEFINITION) return;
                String tool=s.plan.intent()==Intent.TREND ? "analytics.trend" : "analytics.daily";
                if (!new SkillRegistry().select(s.plan.intent()).allowedTools().contains(tool))
                    throw new BusinessException("TOOL_NOT_ALLOWED");
                if (s.plan.intent()==Intent.TREND)
                    s.rows=reader.trend(run.id(),q.date().minusDays(6),q.date());
                else s.rows=reader.query(run.id(),q.date());
                if (s.plan.intent()==Intent.COMPARE || s.plan.intent()==Intent.ATTRIBUTION)
                    s.previousRows=reader.query(run.id(),q.compareDate());
                if (s.rows.isEmpty()) throw new BusinessException("NO_DATA");
                if ((s.plan.intent()==Intent.COMPARE || s.plan.intent()==Intent.ATTRIBUTION) && s.previousRows.isEmpty())
                    throw new BusinessException("NO_COMPARISON_DATA");
            }
            case 3 -> {
                if (s.plan.intent()==Intent.DEFINITION) return;
                // 七日 UV 不能直接求和当作去重用户数，摘要只统计最后一天。
                List<Daily> current=s.plan.intent()==Intent.TREND
                    ? s.rows.stream().filter(row->row.date().equals(q.date())).toList() : s.rows;
                if (s.plan.intent()!=Intent.DEFINITION && current.isEmpty()) throw new BusinessException("NO_DATA");
                s.result=calculator.calculate(current,s.previousRows);
                if (s.plan.intent()==Intent.TREND)
                    s.result=new Result(s.result.current(),null,null,List.of(),s.rows);
                if (s.plan.intent()==Intent.TOP_N) {
                    List<Daily> sorted=s.rows.stream().sorted(Comparator.comparing(Daily::gmv).reversed()).toList();
                    s.result=new Result(s.result.current(),null,null,List.of(),com.ecom.tools.QuestionConstraints.limit(q.question(),sorted));
                }
            }
            case 4 -> {
                if (s.evidence.isEmpty()) throw new BusinessException("EVIDENCE_REQUIRED");
                if (!s.previousRows.isEmpty()) {
                    BigDecimal sum=s.result.contributions().stream().map(Contribution::delta).reduce(BigDecimal.ZERO,BigDecimal::add);
                    if (sum.compareTo(s.result.current().gmv().subtract(s.result.previous().gmv()))!=0)
                        throw new BusinessException("CONTRIBUTION_MISMATCH");
                }
            }
            case 5 -> {
                String conclusion=s.plan.intent()==Intent.DEFINITION
                    ? "GMV：统计已支付订单金额；当前口径不扣退款。"
                    : "目标日 GMV 为 "+s.result.current().gmv()+" 元。";
                if (s.result!=null && s.result.previous()!=null)
                    conclusion+=" 对比日 GMV 为 "+s.result.previous().gmv()+" 元；差额 "+
                        s.result.current().gmv().subtract(s.result.previous().gmv())+" 元。";
                s.report=new Report("电商分析 · "+s.plan.intent(),conclusion,
                    "本地数据为合成数据；品类贡献不是业务因果证据。趋势摘要仅统计目标日，明细包含七日数据。",
                    s.result,s.evidence,s.plan.intent()==Intent.DEFINITION ? "" :
                    reader.generatedSql()!=null?reader.generatedSql():s.plan.intent()==Intent.TREND ? ApprovedSql.TREND : ApprovedSql.DAILY,planner.mode());
            }
            default -> throw new IllegalStateException();
        }
    }
}
