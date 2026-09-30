package com.ecom.api;

import com.ecom.domain.Semantic;
import com.ecom.domain.Understanding;
import com.ecom.domain.BusinessException;
import com.ecom.tools.QuestionUnderstanding;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.security.Principal;
import java.util.*;

/** 确定性语义预检：将可确认的问题约束编译成只读计划，不伪装成大模型推理。 */
@RestController @RequestMapping("/api/questions")
public class QuestionController {
    private final Semantic.Workbench workbench;
    public QuestionController(Semantic.Workbench workbench) {this.workbench=workbench;}
    public record Input(String question,LocalDate date,LocalDate compareDate,String metric) {}
    public record Preview(Understanding.Resolved understanding,boolean ready,boolean queryExecutable,
                          List<Semantic.Query> plans,List<Semantic.Compiled> compiled,List<String> limitations) {}
    public record Data(String period,List<Map<String,Object>> rows) {}
    public record Answer(Preview preview,List<Data> data) {}
    @PostMapping("/preview") public Preview preview(@RequestBody Input input) {
        if(input==null || input.question()==null) throw new BusinessException("INVALID_REQUEST");
        var resolved=QuestionUnderstanding.resolve(input.question(),input.date(),input.compareDate(),input.metric(),Clock.systemUTC());
        var plans=new ArrayList<Semantic.Query>();var compiled=new ArrayList<Semantic.Compiled>();
        var limits=new ArrayList<>(List.of("确定性文本解析，不调用模型；含糊日期/指标须澄清，不会猜测。",
            "本入口只返回查询证据，不把查询结果当成业务因果解释；完整GMV协作报告仍用主分析入口。"));
        boolean numeric=resolved.intents().stream().anyMatch(i->Set.of(Understanding.Intent.QUERY,Understanding.Intent.COMPARE,Understanding.Intent.ATTRIBUTION,Understanding.Intent.TREND,Understanding.Intent.TOP_N).contains(i));
        if(resolved.ready() && numeric && !resolved.metrics().isEmpty()) {
            List<String> dimensions=resolved.intents().contains(Understanding.Intent.TREND)?List.of("stat_date"):
                resolved.intents().contains(Understanding.Intent.TOP_N)?List.of("category"):List.of();
            plans.add(new Semantic.Query(resolved.metrics(),dimensions,resolved.rangeStart(),resolved.rangeEnd(),resolved.category(),500));
            if(resolved.compareDate()!=null && (resolved.intents().contains(Understanding.Intent.COMPARE) || resolved.intents().contains(Understanding.Intent.ATTRIBUTION)))
                plans.add(new Semantic.Query(resolved.metrics(),dimensions,resolved.compareDate(),resolved.compareDate(),resolved.category(),500));
            try {for(var plan:plans) compiled.add(workbench.plan(plan));}
            catch(BusinessException e) {limits.add("计划暂不可执行："+e.code());plans.clear();compiled.clear();}
            if(resolved.intents().contains(Understanding.Intent.TOP_N) && resolved.metrics().size()!=1) {
                limits.add("排名须选定单个排序指标。");plans.clear();compiled.clear();
            }
        }
        return new Preview(resolved,resolved.ready(),!plans.isEmpty(),List.copyOf(plans),List.copyOf(compiled),List.copyOf(limits));
    }
    @PostMapping("/query") public Answer query(Principal user,@RequestBody Input input) {
        Preview preview=preview(input);
        if(!preview.ready() || !preview.queryExecutable()) throw new BusinessException("QUESTION_NEEDS_CLARIFICATION");
        var tables=new ArrayList<Data>();int index=0;
        for(var plan:preview.plans()) {
            List<Map<String,Object>> rows=workbench.query(user.getName(),plan);
            if(preview.understanding().intents().contains(Understanding.Intent.TOP_N)) {
                String metric=plan.metrics().getFirst().toLowerCase(Locale.ROOT);
                var sorted=new ArrayList<>(rows);
                // NULL 是未知值，排名置后；不能转成0抢占排名。
                sorted.sort(Comparator.comparing(r->r.get(metric)==null?null:new java.math.BigDecimal(r.get(metric).toString()),Comparator.nullsLast(Comparator.reverseOrder())));
                int top=preview.understanding().topN()==null?sorted.size():Math.min(sorted.size(),preview.understanding().topN());
                rows=List.copyOf(sorted.subList(0,top));
            }
            tables.add(new Data(index++==0?"current":"previous",rows));
        }
        return new Answer(preview,List.copyOf(tables));
    }
}
