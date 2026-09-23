package com.ecom.knowledge;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Ports.Planner;
import com.ecom.tools.QuestionConstraints;
import static com.ecom.domain.Analysis.*;
import java.util.List;

/** 明确标注的离线规则规划器，用来验证链路，不伪装成 LLM 推理。 */
public final class RulePlanner implements Planner {
    public Plan plan(Request r) {
        String q = r.question().toLowerCase();
        Intent i;
        if (q.contains("定义") || q.contains("口径") || q.contains("是什么")) i = Intent.DEFINITION;
        else if (q.contains("归因") || q.contains("为什么") || q.contains("原因")) i = Intent.ATTRIBUTION;
        else if (q.contains("趋势") || q.contains("七天") || q.contains("7天")) i = Intent.TREND;
        else if (q.contains("top") || q.contains("排名") || q.contains("排行") || QuestionConstraints.topN(r.question()).isPresent()) i = Intent.TOP_N;
        else if (q.contains("对比") || q.contains("环比") || q.contains("比较")) i = Intent.COMPARE;
        else if (q.contains("gmv") || q.contains("成交") || q.contains("订单")) i = Intent.QUERY;
        else throw new BusinessException("UNSUPPORTED_QUESTION");
        return new Plan(i, List.of("读取指标口径", "查询受控数据", "计算并核验报告"), mode());
    }
    public String mode() { return "OFFLINE_RULES"; }
}
