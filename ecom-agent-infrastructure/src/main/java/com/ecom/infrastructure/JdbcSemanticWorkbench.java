package com.ecom.infrastructure;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Semantic.*;
import com.ecom.tools.SemanticSqlCompiler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.util.*;

/** 持久化指标注册表 + 参数化查询工作台。所有数据写入仅是元数据/审计，不改业务订单。 */
@Repository
public class JdbcSemanticWorkbench implements Workbench {
    private final JdbcTemplate db,reader;
    private final SemanticSqlCompiler compiler = new SemanticSqlCompiler();
    public JdbcSemanticWorkbench(JdbcTemplate db,@Qualifier("readerDataSource") DataSource source) {
        this.db=db;this.reader=new JdbcTemplate(source);reader.setQueryTimeout(5);reader.setMaxRows(501);
    }
    public Model model() {
        var metrics=db.query("SELECT code,name,formula_type,numerator,denominator,unit,description FROM metric_definition ORDER BY code",
            (r,n)->new MetricDef(r.getString(1),r.getString(2),Formula.valueOf(r.getString(3)),r.getString(4),r.getString(5),r.getString(6),r.getString(7)));
        return new Model("daily_commerce","analytics_daily",List.of("stat_date","category"),
            List.of("gmv","paid_orders","uv"),List.copyOf(metrics),SemanticSqlCompiler.VERSION);
    }
    public Compiled plan(Query query) {return compiler.compile(model(),query);}
    public List<Map<String,Object>> query(String owner, Query query) {
        Compiled c=plan(query);
        // 只执行本次可信编译器的产物，外界不能提交 SQL 字符串。
        compiler.validateGenerated(c.sql(),plan(query).sql());
        long start=System.nanoTime();String auditId=UUID.randomUUID().toString();
        try {
            var rows=reader.queryForList(c.sql(),c.parameters().toArray());
            if(rows.size()>query.limit()) throw new BusinessException("QUERY_RESULT_LIMIT_EXCEEDED");
            audit(owner,auditId,c.sql(),rows.size(),"SUCCEEDED",start);return rows;
        } catch(RuntimeException e) {audit(owner,auditId,c.sql(),0,"FAILED",start);throw e;}
    }
    private void audit(String owner,String id,String sql,int rows,String status,long start) {
        db.update("INSERT INTO tool_call_audit(run_id,tool_name,sql_template,row_count,status,elapsed_ms) VALUES(?,?,?,?,?,?)",
            id,"semantic.read",sql,rows,status,(System.nanoTime()-start)/1_000_000);
        db.update("INSERT INTO semantic_change_audit(owner,action,target) VALUES(?,?,?)",owner,"QUERY",id);
    }
    @Transactional
    public MetricDef register(String owner,MetricDef d) {
        SemanticSqlCompiler.validateDefinition(d);
        if(db.queryForObject("SELECT COUNT(*) FROM metric_definition",Integer.class)>=100) throw new BusinessException("METRIC_CAPACITY");
        try {
            db.update("INSERT INTO metric_definition(code,name,formula_type,numerator,denominator,unit,description) VALUES(?,?,?,?,?,?,?)",
                d.code(),d.name(),d.formula().name(),d.numerator(),d.denominator(),d.unit(),d.description());
        } catch(DuplicateKeyException e) {throw new BusinessException("METRIC_ALREADY_EXISTS");}
        db.update("INSERT INTO semantic_change_audit(owner,action,target) VALUES(?,?,?)",owner,"REGISTER_METRIC",d.code());return d;
    }
    public List<KnowledgeHit> recall(String metric,String question) {
        if(metric==null || !metric.matches("[A-Z][A-Z0-9_]{0,31}") || question==null || question.length()>2000)
            throw new BusinessException("INVALID_REQUEST");
        var hits=new ArrayList<>(db.query("SELECT id,layer_code,title,content,version FROM knowledge_document WHERE metric_code=? OR metric_code='*' ORDER BY layer_code,id LIMIT 32",
            (r,n)->new KnowledgeHit(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),metric));
        model().metrics().stream().filter(m->m.code().equals(metric)).findFirst().ifPresent(m->hits.add(new KnowledgeHit("metric-"+metric,"L5_METRIC",m.name(),m.description(),"1")));
        hits.sort(Comparator.<KnowledgeHit>comparingInt(h->relevance(h,question)).reversed().thenComparing(KnowledgeHit::id));
        return hits.stream().limit(12).toList();
    }
    private static int relevance(KnowledgeHit hit,String question) {
        int score=0;
        for(String term:List.of("口径","归因","血缘","SQL","UV","GMV","订单","转化","模型"))
            if(question.toUpperCase(Locale.ROOT).contains(term) && (hit.title()+hit.text()).toUpperCase(Locale.ROOT).contains(term)) score++;
        return score;
    }
    @Transactional
    public void remember(String owner,String summary) {
        if(summary==null || summary.length()>1000 || summary.contains("sk-") || summary.toLowerCase(Locale.ROOT).contains("api_key"))
            throw new BusinessException("INVALID_MEMORY");
        // 会话摘要由用户明确保存，不自动收集对话或密钥。主键中 owner 强制隔离。
        int changed=db.update("UPDATE conversation_memory SET summary=?,version=version+1 WHERE owner=? AND session_id='semantic-workbench'",summary,owner);
        if(changed==0) {
            try {db.update("INSERT INTO conversation_memory(owner,session_id,summary,version) VALUES(?,'semantic-workbench',?,1)",owner,summary);}
            catch(DuplicateKeyException e) {throw new BusinessException("MEMORY_VERSION_CONFLICT");}
        }
    }
    public String memory(String owner) {
        return db.query("SELECT summary FROM conversation_memory WHERE owner=? AND session_id='semantic-workbench'",(r,n)->r.getString(1),owner).stream().findFirst().orElse("");
    }
}
