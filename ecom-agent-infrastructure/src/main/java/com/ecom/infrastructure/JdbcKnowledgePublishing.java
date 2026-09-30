package com.ecom.infrastructure;

import com.ecom.domain.BusinessException;
import com.ecom.domain.KnowledgePublishing.*;
import com.ecom.domain.Lineage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** All state changes, publication and audit entries commit atomically. SQL snippets are never executed. */
@Repository
public class JdbcKnowledgePublishing implements Service {
    private static final Pattern SECRET = Pattern.compile(
        "(?i)(?:\\bsk-[a-z0-9_-]{8,}|(?:api[_ -]?key|authorization)\\s*[:=]\\s*\\S+)");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Lineage.Service lineage;

    public JdbcKnowledgePublishing(JdbcTemplate db, ObjectMapper json, Lineage.Service lineage) {
        this.db = db; this.json = json; this.lineage = lineage;
    }

    @Override @Transactional
    public Draft create(String owner, Content content) {
        validateOwner(owner);
        Validated input = validate(content);
        return insert(owner, input, 1, null, "CREATE");
    }

    @Override
    public List<Draft> list(String owner) {
        validateOwner(owner);
        return db.query("SELECT * FROM knowledge_draft WHERE owner=? ORDER BY created_at DESC,id DESC LIMIT 100",
            (r,n) -> map(r), owner);
    }

    @Override
    public Draft get(String owner, String id) {
        validateOwner(owner);
        // A foreign draft and a nonexistent draft deliberately return the same error.
        return db.query("SELECT * FROM knowledge_draft WHERE owner=? AND id=?", (r,n) -> map(r), owner, id)
            .stream().findFirst().orElseThrow(() -> new BusinessException("KNOWLEDGE_NOT_FOUND"));
    }

    @Override @Transactional
    public Draft edit(String owner, String id, Edit edit) {
        Draft current = get(owner, id);
        if (edit == null) throw invalid();
        checkDraft(current, edit.expectedVersion());
        Validated input = validate(edit.content());
        Content value = input.content();
        checkRevisionIdentity(current, value);
        int changed = db.update("UPDATE knowledge_draft SET layer_code=?,metric_code=?,title=?,content=?,sql_snippet=?,sql_lineage_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE owner=? AND id=? AND status='DRAFT' AND version=?",
            value.layer().name(), value.metric(), value.title(), value.content(), value.sql(), encode(input.lineage()), owner, id, edit.expectedVersion());
        if (changed != 1) throw conflict();
        Draft next = get(owner, id);
        appendAudit(next, "EDIT", "Saved a validated draft; not published.");
        return next;
    }

    @Override @Transactional
    public Draft publish(String owner, String id, Review review) {
        Draft current = get(owner, id);
        validateReview(review);
        checkDraft(current, review.expectedVersion());
        // Revalidate against the CURRENT metric registry and schema at the moment of approval.
        Validated input = validate(contentOf(current));
        checkRevisionIdentity(current, input.content());
        int changed = db.update("UPDATE knowledge_draft SET status='PUBLISHED',version=version+1,sql_lineage_json=?,updated_at=CURRENT_TIMESTAMP WHERE owner=? AND id=? AND status='DRAFT' AND version=?",
            encode(input.lineage()), owner, id, review.expectedVersion());
        if (changed != 1) throw conflict();
        if (current.supersedesDocumentId() != null) {
            // Competing revisions cannot both replace the same active version.
            int retired = db.update("UPDATE knowledge_document SET active=FALSE WHERE id=? AND active=TRUE", current.supersedesDocumentId());
            if (retired != 1) throw new BusinessException("KNOWLEDGE_REVISION_CONFLICT");
        }
        String documentId = "pub-" + UUID.randomUUID();
        String publishedContent = current.content();
        if (!current.sql().isEmpty()) publishedContent += "\n\nSQL snippet (AST checked; never executed):\n" + current.sql();
        db.update("INSERT INTO knowledge_document(id,title,content,version,metric_code,layer_code,active) VALUES(?,?,?,?,?,?,TRUE)",
            documentId, current.title(), publishedContent, Integer.toString(current.revision()), current.metric(), current.layer().name());
        db.update("UPDATE knowledge_draft SET published_document_id=? WHERE owner=? AND id=?", documentId, owner, id);
        Draft next = get(owner, id);
        appendAudit(next, "PUBLISH", review.note().trim());
        return next;
    }

    @Override @Transactional
    public Draft reject(String owner, String id, Review review) {
        Draft current = get(owner, id);
        validateReview(review);
        checkDraft(current, review.expectedVersion());
        if (db.update("UPDATE knowledge_draft SET status='REJECTED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE owner=? AND id=? AND status='DRAFT' AND version=?",
            owner, id, review.expectedVersion()) != 1) throw conflict();
        Draft next = get(owner, id);
        appendAudit(next, "REJECT", review.note().trim());
        return next;
    }

    @Override @Transactional
    public Draft revise(String owner, String id, Revision revision) {
        Draft current = get(owner, id);
        if (revision == null) throw invalid();
        if (current.status() != Status.PUBLISHED) throw new BusinessException("KNOWLEDGE_STATE_CONFLICT");
        if (current.version() != revision.expectedVersion()) throw conflict();
        Boolean active = db.queryForObject("SELECT active FROM knowledge_document WHERE id=?", Boolean.class, current.publishedDocumentId());
        if (!Boolean.TRUE.equals(active)) throw new BusinessException("KNOWLEDGE_REVISION_CONFLICT");
        if (current.revision() == Integer.MAX_VALUE) throw new BusinessException("KNOWLEDGE_REVISION_CONFLICT");
        return insert(owner, validate(contentOf(current)), current.revision() + 1, current.publishedDocumentId(), "REVISE");
    }

    @Override
    public List<Audit> audit(String owner, String id) {
        get(owner, id);
        return db.query("SELECT action,version,note,created_at FROM knowledge_publishing_audit WHERE owner=? AND draft_id=? ORDER BY id",
            (r,n) -> new Audit(r.getString(1), r.getLong(2), r.getString(3), r.getTimestamp(4).toLocalDateTime()), owner, id);
    }

    private Draft insert(String owner, Validated input, int revision, String supersedes, String action) {
        String id = UUID.randomUUID().toString();
        Content value = input.content();
        db.update("INSERT INTO knowledge_draft(id,owner,layer_code,metric_code,title,content,sql_snippet,status,version,revision,supersedes_document_id,sql_lineage_json) VALUES(?,?,?,?,?,?,?,'DRAFT',1,?,?,?)",
            id, owner, value.layer().name(), value.metric(), value.title(), value.content(), value.sql(), revision, supersedes, encode(input.lineage()));
        Draft draft = get(owner, id);
        appendAudit(draft, action, "Draft only; explicit human confirmation is required before publication.");
        return draft;
    }

    private record Validated(Content content, Lineage.Result lineage) {}
    private record Identity(String metric, Layer layer) {}

    private Validated validate(Content input) {
        if (input == null || input.layer() == null) throw invalid();
        String metric = required(input.metric(), 32);
        if (!metric.equals("*") && (!metric.matches("[A-Z][A-Z0-9_]{0,31}") ||
            db.queryForObject("SELECT COUNT(*) FROM metric_definition WHERE code=?", Integer.class, metric) != 1)) {
            throw new BusinessException("KNOWLEDGE_METRIC_NOT_REGISTERED");
        }
        String title = required(input.title(), 256);
        String content = required(input.content(), 6000);
        String sql = input.sql() == null ? "" : input.sql().trim();
        if (sql.length() > 16000 || SECRET.matcher(title + "\n" + content + "\n" + sql).find()) throw invalid();
        // analyze() accepts only the server-controlled schema and performs no SQL execution.
        Lineage.Result checked = sql.isEmpty() ? null : lineage.analyze(sql);
        return new Validated(new Content(input.layer(), metric, title, content, sql), checked);
    }

    private void validateReview(Review review) {
        if (review == null || !review.confirmed()) throw new BusinessException("KNOWLEDGE_CONFIRMATION_REQUIRED");
        String note = required(review.note(), 1000);
        if (SECRET.matcher(note).find()) throw invalid();
    }

    private void checkDraft(Draft draft, long expectedVersion) {
        if (draft.status() != Status.DRAFT) throw new BusinessException("KNOWLEDGE_STATE_CONFLICT");
        if (draft.version() != expectedVersion || expectedVersion == Long.MAX_VALUE) throw conflict();
    }

    private void checkRevisionIdentity(Draft draft, Content proposed) {
        if (draft.supersedesDocumentId() == null) return;
        var original = db.query("SELECT metric_code,layer_code FROM knowledge_document WHERE id=?",
            (r,n) -> new Identity(r.getString("metric_code"), Layer.valueOf(r.getString("layer_code"))),
            draft.supersedesDocumentId()).stream().findFirst()
            .orElseThrow(() -> new BusinessException("KNOWLEDGE_REVISION_CONFLICT"));
        if (!original.metric().equals(proposed.metric()) || original.layer() != proposed.layer())
            throw new BusinessException("KNOWLEDGE_REVISION_IDENTITY_CONFLICT");
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) throw invalid();
        return value.trim();
    }

    private static void validateOwner(String owner) { required(owner, 128); }
    private static Content contentOf(Draft draft) { return new Content(draft.layer(), draft.metric(), draft.title(), draft.content(), draft.sql()); }
    private static BusinessException invalid() { return new BusinessException("INVALID_KNOWLEDGE_CONTENT"); }
    private static BusinessException conflict() { return new BusinessException("KNOWLEDGE_VERSION_CONFLICT"); }

    private String encode(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("KNOWLEDGE_SERIALIZATION_FAILED"); }
    }

    private Draft map(ResultSet r) throws SQLException {
        Lineage.Result checked = null;
        String serialized = r.getString("sql_lineage_json");
        if (serialized != null) {
            try { checked = json.readValue(serialized, Lineage.Result.class); }
            catch (Exception e) { throw new IllegalStateException("KNOWLEDGE_READ_FAILED"); }
        }
        return new Draft(r.getString("id"), r.getString("owner"), Layer.valueOf(r.getString("layer_code")),
            r.getString("metric_code"), r.getString("title"), r.getString("content"), r.getString("sql_snippet"),
            Status.valueOf(r.getString("status")), r.getLong("version"), r.getInt("revision"),
            r.getString("supersedes_document_id"), r.getString("published_document_id"), checked,
            r.getTimestamp("created_at").toLocalDateTime(), r.getTimestamp("updated_at").toLocalDateTime());
    }

    private void appendAudit(Draft draft, String action, String note) {
        db.update("INSERT INTO knowledge_publishing_audit(draft_id,owner,action,version,note,snapshot_json) VALUES(?,?,?,?,?,?)",
            draft.id(), draft.owner(), action, draft.version(), note, encode(draft));
    }
}
