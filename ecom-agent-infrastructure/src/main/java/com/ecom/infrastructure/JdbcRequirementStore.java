package com.ecom.infrastructure;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Requirements.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Repository
public class JdbcRequirementStore implements Store {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public JdbcRequirementStore(JdbcTemplate db,ObjectMapper json) {this.db=db;this.json=json;}
    public Optional<Review> find(String owner,String id) {
        return db.query("SELECT review_json FROM requirement_review WHERE owner=? AND requirement_id=?",(rs,n)->{
            try {return json.readValue(rs.getString(1),Review.class);}catch(Exception e){throw new IllegalStateException("REVIEW_READ_FAILED");}
        },owner,id).stream().findFirst();
    }
    @Transactional
    public Review save(String owner,String id,Review input) {
        Review next=new Review(input.implemented(),input.extent(),input.verification(),input.rework(),input.notes(),input.version()+1);
        String body;
        try {body=json.writeValueAsString(next);}catch(Exception e){throw new IllegalStateException("REVIEW_WRITE_FAILED");}
        if(input.version()==0) {
            try {db.update("INSERT INTO requirement_review(owner,requirement_id,version,review_json) VALUES(?,?,?,?)",owner,id,next.version(),body);}
            catch(DuplicateKeyException conflict) {throw new BusinessException("REVIEW_VERSION_CONFLICT");}
        } else if(db.update("UPDATE requirement_review SET version=?,review_json=?,updated_at=CURRENT_TIMESTAMP WHERE owner=? AND requirement_id=? AND version=?",
            next.version(),body,owner,id,input.version())!=1) throw new BusinessException("REVIEW_VERSION_CONFLICT");
        db.update("INSERT INTO requirement_review_audit(owner,requirement_id,version,review_json) VALUES(?,?,?,?)",owner,id,next.version(),body);
        return next;
    }
}
