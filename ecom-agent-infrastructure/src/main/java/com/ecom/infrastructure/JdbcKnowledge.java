package com.ecom.infrastructure;

import com.ecom.domain.Analysis.*;
import com.ecom.domain.Ports.Knowledge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class JdbcKnowledge implements Knowledge {
    private final MetricMapper metrics;
    private final JdbcTemplate db;
    public JdbcKnowledge(MetricMapper metrics,JdbcTemplate db){this.metrics=metrics;this.db=db;}
    public List<Metric> metrics(){return metrics.findAll();}
    public List<Evidence> context(String question,String metric) {
        // 口径优先，随后是有来源标识的模型/SQL/字段血缘；不是向量或 LightRAG 检索。
        var evidence=new ArrayList<>(recall(question,metric));
        evidence.addAll(db.query("SELECT id,title,content,version FROM knowledge_document WHERE (metric_code=? OR metric_code='*') AND layer_code<>'L3_RULE' ORDER BY layer_code,id LIMIT 6",
            (r,n)->new Evidence(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),metric));
        evidence.addAll(db.query("SELECT code,name,description FROM metric_definition WHERE code=?",
            (r,n)->new Evidence("metric-"+r.getString(1),"L5 指标注册："+r.getString(2),r.getString(3),"1"),metric));
        return List.copyOf(evidence);
    }
    public List<Evidence> recall(String question,String metric) {
        if(metric==null || !metric.matches("[A-Z][A-Z0-9_]{0,31}")) throw new com.ecom.domain.BusinessException("METRIC_NOT_SUPPORTED");
        String query=question==null?"":question;
        // 主链先限定指标口径层，再按问题中的术语排序；不再把全部文档混入模型上下文。
        var found=db.query("SELECT id,title,content,version FROM knowledge_document WHERE metric_code=? AND layer_code='L3_RULE' ORDER BY id LIMIT 24",
            (r,n)->new Evidence(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),metric);
        return found.stream().sorted(Comparator.<Evidence>comparingInt(e -> {
            int score=0;for(String term:List.of("归因","口径","UV","订单","转化")) if(query.contains(term) && (e.title()+e.text()).contains(term)) score++;
            return score;
        }).reversed().thenComparing(Evidence::id)).limit(8).toList();
    }
}
