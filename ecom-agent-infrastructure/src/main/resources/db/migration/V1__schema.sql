CREATE TABLE biz_order (
  id BIGINT PRIMARY KEY, paid_date DATE NOT NULL, category VARCHAR(32) NOT NULL,
  customer_ref VARCHAR(64) NOT NULL, paid_amount DECIMAL(18,2) NOT NULL,
  status VARCHAR(16) NOT NULL, CHECK (paid_amount >= 0)
);
CREATE INDEX idx_order_date_category ON biz_order(paid_date, category);
CREATE TABLE traffic_daily (
  stat_date DATE NOT NULL, category VARCHAR(32) NOT NULL, uv BIGINT NOT NULL,
  PRIMARY KEY(stat_date, category), CHECK (uv >= 0)
);
-- Seed 客群按品类互斥，所以该数据集 UV 可加总。真实数据跨品类须先按用户去重。
CREATE VIEW analytics_daily AS
SELECT t.stat_date, t.category, COALESCE(o.gmv, 0) AS gmv,
       COALESCE(o.paid_orders, 0) AS paid_orders, t.uv
FROM traffic_daily t LEFT JOIN
(SELECT paid_date, category, SUM(paid_amount) AS gmv, COUNT(*) AS paid_orders
 FROM biz_order WHERE status='PAID' GROUP BY paid_date, category) o
ON t.stat_date=o.paid_date AND t.category=o.category;

CREATE TABLE semantic_metric (
  code VARCHAR(32) PRIMARY KEY, name VARCHAR(64) NOT NULL,
  definition VARCHAR(2000) NOT NULL, formula VARCHAR(512) NOT NULL, unit VARCHAR(32) NOT NULL
);
CREATE TABLE knowledge_document (
  id VARCHAR(64) PRIMARY KEY, title VARCHAR(256) NOT NULL, content TEXT NOT NULL, version VARCHAR(32) NOT NULL
);
CREATE TABLE agent_run (
  id VARCHAR(36) PRIMARY KEY, owner VARCHAR(128) NOT NULL, request_key VARCHAR(128) NOT NULL,
  request_json TEXT NOT NULL, request_hash VARCHAR(64) NOT NULL, mode VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL, snapshot_json TEXT NOT NULL, report_json TEXT,
  error_code VARCHAR(64), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(owner, request_key)
);
CREATE INDEX idx_run_owner_created ON agent_run(owner, created_at);
CREATE TABLE agent_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(36) NOT NULL,
  node VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
  detail TEXT NOT NULL, elapsed_ms BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(run_id) REFERENCES agent_run(id)
);
CREATE INDEX idx_event_run_id ON agent_event(run_id, id);
CREATE TABLE tool_call_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(36) NOT NULL,
  tool_name VARCHAR(64) NOT NULL, sql_template TEXT, row_count INTEGER,
  status VARCHAR(32) NOT NULL, elapsed_ms BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 以下表契约已迁移；长时记忆与离线自动评测 worker 在后续接入。
CREATE TABLE conversation_memory (
  owner VARCHAR(128) NOT NULL, session_id VARCHAR(36) NOT NULL,
  summary TEXT NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(owner, session_id)
);
CREATE TABLE eval_case (
  id VARCHAR(64) PRIMARY KEY, case_type VARCHAR(32) NOT NULL,
  request_json TEXT NOT NULL, expected_json TEXT NOT NULL
);
CREATE TABLE worker_job (
  id VARCHAR(36) PRIMARY KEY, kind VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL, status VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
  available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

