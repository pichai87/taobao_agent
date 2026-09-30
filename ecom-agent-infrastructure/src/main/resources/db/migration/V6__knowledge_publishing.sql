-- Existing seed knowledge remains active. Human drafts are stored separately and are not retrievable.
ALTER TABLE knowledge_document ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
-- A 6000-character body plus a 16000-character SQL example can exceed MySQL TEXT's byte limit.
ALTER TABLE knowledge_document MODIFY COLUMN content MEDIUMTEXT NOT NULL;
CREATE INDEX idx_knowledge_active_layer ON knowledge_document(active,metric_code,layer_code);

CREATE TABLE knowledge_draft (
  id VARCHAR(36) PRIMARY KEY, owner VARCHAR(128) NOT NULL,
  layer_code VARCHAR(16) NOT NULL, metric_code VARCHAR(32) NOT NULL,
  title VARCHAR(256) NOT NULL, content TEXT NOT NULL, sql_snippet TEXT NOT NULL,
  status VARCHAR(16) NOT NULL, version BIGINT NOT NULL, revision INTEGER NOT NULL,
  supersedes_document_id VARCHAR(64), published_document_id VARCHAR(64), sql_lineage_json MEDIUMTEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(supersedes_document_id) REFERENCES knowledge_document(id),
  FOREIGN KEY(published_document_id) REFERENCES knowledge_document(id),
  CHECK(status IN ('DRAFT','PUBLISHED','REJECTED')),
  CHECK(layer_code IN ('L1_MODEL','L2_SQL','L3_RULE','L4_LINEAGE')),
  CHECK(version >= 1), CHECK(revision >= 1)
);
CREATE INDEX idx_knowledge_draft_owner ON knowledge_draft(owner,created_at);

CREATE TABLE knowledge_publishing_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, draft_id VARCHAR(36) NOT NULL,
  owner VARCHAR(128) NOT NULL, action VARCHAR(16) NOT NULL, version BIGINT NOT NULL,
  note VARCHAR(1000) NOT NULL, snapshot_json MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(draft_id) REFERENCES knowledge_draft(id)
);
CREATE INDEX idx_knowledge_publishing_audit ON knowledge_publishing_audit(draft_id,id);
