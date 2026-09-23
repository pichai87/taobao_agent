package com.ecom.infrastructure;

import com.ecom.domain.*;
import com.ecom.domain.Ports.RunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.ecom.domain.Analysis.*;

@Repository
public class JdbcRunStore implements RunStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public JdbcRunStore(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    public Run create(String owner, String key, Request request, String mode) {
        String body=encode(request);
        // 兼容新增可选模型字段之前的离线幂等键，不因 null 字段改变历史请求的 hash。
        com.fasterxml.jackson.databind.node.ObjectNode node=json.valueToTree(request);
        if(request.modelSessionId()==null) node.remove("modelSessionId");
        if(request.executionMode()==null) node.remove("executionMode");
        body=encode(node);
        String hash=hash(body), id=UUID.randomUUID().toString();
        Status initial=request.reviewRequired()?Status.WAITING_FOR_REVIEW:Status.QUEUED;
        try {
            db.update("INSERT INTO agent_run(id,owner,request_key,request_json,request_hash,mode,status,snapshot_json) VALUES(?,?,?,?,?,?,?,?)",
                    id,owner,key,body,hash,mode,initial.name(),encode(new Snapshot()));
        } catch (DuplicateKeyException duplicate) {
            var rows=db.queryForList("SELECT id,request_hash FROM agent_run WHERE owner=? AND request_key=?",owner,key);
            // ColumnMapRowMapper 的键不区分大小写。
            if(rows.isEmpty() || !hash.equals(rows.getFirst().get("request_hash")))
                throw new BusinessException("IDEMPOTENCY_CONFLICT");
            id=rows.getFirst().get("id").toString();
        }
        return find(id,owner).orElseThrow();
    }
    public Optional<Run> find(String id,String owner) {
        return db.query("SELECT * FROM agent_run WHERE id=? AND owner=?",this::row,id,owner).stream().findFirst();
    }
    public List<Run> list(String owner) {
        return db.query("SELECT * FROM agent_run WHERE owner=? ORDER BY created_at DESC LIMIT 50",this::row,owner);
    }
    public boolean transition(String id,Status from,Status to) {
        return db.update("UPDATE agent_run SET status=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=?",to.name(),id,from.name())==1;
    }
    public Snapshot snapshot(String id) {
        return decode(db.queryForObject("SELECT snapshot_json FROM agent_run WHERE id=?",String.class,id),Snapshot.class);
    }
    public void checkpoint(String id,Snapshot state) {
        if(db.update("UPDATE agent_run SET snapshot_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",encode(state),id)!=1)
            throw new BusinessException("RUN_STOPPED");
    }
    public void succeed(String id,Report report) {
        db.update("UPDATE agent_run SET report_json=?,status='SUCCEEDED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",encode(report),id);
    }
    public void fail(String id,String code) {
        db.update("UPDATE agent_run SET status='FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('RUNNING','QUEUED')",code,id);
    }
    @Transactional
    public void failWithEvent(String id,String code) {
        if(db.update("UPDATE agent_run SET status='FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('RUNNING','QUEUED')",code,id)==1)
            event(id,"run","STOPPED",code,0);
    }
    @Transactional
    public boolean cancelWithEvent(String id,Status from) {
        if(!transition(id,from,Status.CANCELLED)) return false;
        event(id,"run","CANCELLED","任务所有者取消",0);return true;
    }
    public void event(String id,String node,String status,String detail,long elapsed) {
        db.update("INSERT INTO agent_event(run_id,node,status,detail,elapsed_ms) VALUES(?,?,?,?,?)",id,node,status,detail,elapsed);
    }
    public List<Event> events(String id,long after) {
        return db.query("SELECT * FROM agent_event WHERE run_id=? AND id>? ORDER BY id LIMIT 1000",
            (rs,n)->new Event(rs.getLong("id"),rs.getString("node"),rs.getString("status"),rs.getString("detail"),rs.getLong("elapsed_ms")),id,after);
    }
    public void recoverInterrupted() {
        db.update("UPDATE agent_run SET status='INTERRUPTED',error_code='PROCESS_RESTARTED' WHERE status IN ('RUNNING','QUEUED')");
    }
    private Run row(ResultSet rs,int n)throws SQLException {
        String report=rs.getString("report_json");
        return new Run(rs.getString("id"),rs.getString("owner"),decode(rs.getString("request_json"),Request.class),
            Status.valueOf(rs.getString("status")),report==null?null:decode(report,Report.class),rs.getString("error_code"),rs.getString("mode"));
    }
    private String encode(Object v) { try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("STATE_SERIALIZATION_FAILED",e);} }
    private <T>T decode(String v,Class<T> c){try{return json.readValue(v,c);}catch(Exception e){throw new IllegalStateException("STATE_DESERIALIZATION_FAILED",e);} }
    private String hash(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
}
