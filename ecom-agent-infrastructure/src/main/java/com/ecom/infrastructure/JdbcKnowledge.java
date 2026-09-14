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
    public List<Evidence> recall(String question,String metric) {
        // SQL 检索模式明确返回原始规则；向量检索会通过同一接口替换。
        return db.query("SELECT id,title,content,version FROM knowledge_document ORDER BY id",
            (r,n)->new Evidence(r.getString(1),r.getString(2),r.getString(3),r.getString(4)));
    }
}

