package com.ecom.knowledge;

import java.util.*;
import com.ecom.domain.Analysis.Intent;

/** Skill（技能）：经过审核的任务说明与工具清单；不意味着额外创建一个智能体。 */
public final class SkillRegistry {
    public record Skill(String name, String version, Set<String> allowedTools, String instruction) {}
    public Skill select(Intent intent) {
        return switch (intent) {
            case DEFINITION -> new Skill("metric-definition", "1", Set.of("knowledge.recall"), "解释已审核指标口径并附证据。");
            case ATTRIBUTION, COMPARE -> new Skill("gmv-comparison", "1", Set.of("analytics.daily", "analysis.contribution"), "查询两天的数据，计算贡献，不能宣称统计相关性等同因果。");
            case TREND -> new Skill("gmv-trend", "1", Set.of("analytics.trend"), "展示七日指标走势，标注数据缺口。");
            default -> new Skill("gmv-query", "1", Set.of("analytics.daily"), "查询指定日期的已支付 GMV。");
        };
    }
}

