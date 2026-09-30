package com.ecom.domain;

import java.time.LocalDateTime;
import java.util.List;

/** Local, owner-scoped human publishing; not an enterprise two-person approval system. */
public final class KnowledgePublishing {
    private KnowledgePublishing() {}

    public enum Layer { L1_MODEL, L2_SQL, L3_RULE, L4_LINEAGE }
    public enum Status { DRAFT, PUBLISHED, REJECTED }

    public record Content(Layer layer, String metric, String title, String content, String sql) {}
    public record Edit(long expectedVersion, Content content) {}
    public record Review(long expectedVersion, boolean confirmed, String note) {}
    public record Revision(long expectedVersion) {}
    public record Draft(String id, String owner, Layer layer, String metric, String title,
                        String content, String sql, Status status, long version, int revision,
                        String supersedesDocumentId, String publishedDocumentId,
                        Lineage.Result sqlLineage, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Audit(String action, long version, String note, LocalDateTime createdAt) {}

    public interface Service {
        Draft create(String owner, Content content);
        List<Draft> list(String owner);
        Draft get(String owner, String id);
        Draft edit(String owner, String id, Edit edit);
        Draft publish(String owner, String id, Review review);
        Draft reject(String owner, String id, Review review);
        Draft revise(String owner, String id, Revision revision);
        List<Audit> audit(String owner, String id);
    }
}
