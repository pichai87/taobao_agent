CREATE TABLE requirement_review (
  owner VARCHAR(128) NOT NULL,
  requirement_id VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  review_json TEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(owner, requirement_id)
);
CREATE TABLE requirement_review_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner VARCHAR(128) NOT NULL,
  requirement_id VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  review_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_review_audit_owner ON requirement_review_audit(owner, requirement_id);
