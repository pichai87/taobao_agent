package com.ecom.infrastructure;

import com.ecom.domain.Analysis.*;
import com.ecom.domain.Ports.Knowledge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class JdbcKnowledge implements Knowledge {
    private static final String TRUNCATED = "…（模型上下文已截断；原文仍保存在知识库）";
    private final MetricMapper metrics;
    private final JdbcTemplate db;
    private final com.ecom.domain.Lineage.Service lineage;
    public JdbcKnowledge(MetricMapper metrics,JdbcTemplate db,com.ecom.domain.Lineage.Service lineage){this.metrics=metrics;this.db=db;this.lineage=lineage;}
    public List<Metric> metrics(){return metrics.findAll();}
    public List<Evidence> context(String question,String metric) {
        // 口径优先，随后保留指标及血缘的固定预算，最后放入模型/SQL 文档。
        var evidence=new ArrayList<>(recall(question,metric));
        evidence.addAll(db.query("SELECT code,name,description FROM metric_definition WHERE code=?",
            (r,n)->new Evidence("metric-"+r.getString(1),"L5 指标注册："+r.getString(2),r.getString(3),"1"),metric));
        var source=lineage.model();
        source.lineage().columns().stream().filter(c->List.of("gmv","paid_orders","uv").contains(c.output())).forEach(c ->
            evidence.add(new Evidence("ast-"+c.output(),"AST 字段血缘："+c.output(),c.transformation()+"；字段来源="+c.sources()+"；行来源="+c.rowSources(),source.version())));
        evidence.add(new Evidence("ast-filters","AST 筛选与关联依据",source.lineage().predicates().toString(),source.version()));
        evidence.addAll(db.query("SELECT id,title,content,version FROM knowledge_document WHERE active=TRUE AND (metric_code=? OR metric_code='*') AND layer_code<>'L3_RULE' ORDER BY layer_code,id LIMIT 6",
            (r,n)->new Evidence(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),metric));
        // 只压缩送给 Agent 的副本；知识库原文及审核快照保持完整。
        return bounded(evidence,14_000,1_200);
    }
    public List<Evidence> recall(String question,String metric) {
        if(metric==null || !metric.matches("[A-Z][A-Z0-9_]{0,31}")) throw new com.ecom.domain.BusinessException("METRIC_NOT_SUPPORTED");
        String query=question==null?"":question;
        // 主链先限定指标口径层，再按问题中的术语排序；不再把全部文档混入模型上下文。
        var found=db.query("SELECT id,title,content,version FROM knowledge_document WHERE active=TRUE AND (metric_code=? OR metric_code='*') AND layer_code='L3_RULE' ORDER BY id LIMIT 24",
            (r,n)->new Evidence(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),metric);
        return bounded(found.stream().sorted(Comparator.<Evidence>comparingInt(e -> {
            int score=0;for(String term:List.of("归因","口径","UV","订单","转化")) if(query.contains(term) && (e.title()+e.text()).contains(term)) score++;
            return score;
        }).reversed().thenComparing(Evidence::id)).limit(8).toList(),8_000,1_200);
    }
    private static List<Evidence> bounded(List<Evidence> source,int totalChars,int perTextChars) {
        var answer=new ArrayList<Evidence>();
        int remaining=totalChars;
        for(Evidence item:source) {
            int metadata=item.id().length()+item.title().length()+item.version().length()+32;
            int allowance=Math.min(perTextChars,remaining-metadata);
            if(allowance<=TRUNCATED.length()) break;
            String value=item.text();
            if(value.length()>allowance) value=value.substring(0,allowance-TRUNCATED.length())+TRUNCATED;
            answer.add(new Evidence(item.id(),item.title(),value,item.version()));
            remaining-=metadata+value.length();
        }
        return List.copyOf(answer);
    }
}
