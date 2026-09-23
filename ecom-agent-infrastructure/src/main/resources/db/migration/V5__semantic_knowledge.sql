CREATE TABLE metric_definition (
  code VARCHAR(32) PRIMARY KEY, name VARCHAR(64) NOT NULL, formula_type VARCHAR(16) NOT NULL,
  numerator VARCHAR(32) NOT NULL, denominator VARCHAR(32), unit VARCHAR(32) NOT NULL,
  description VARCHAR(2000) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO metric_definition(code,name,formula_type,numerator,denominator,unit,description) VALUES
('GMV','支付金额','SUM','gmv',NULL,'元','仅统计 PAID 订单，按支付日期汇总。不是扣除退款后的净收入。'),
('ORDERS','支付订单数','SUM','paid_orders',NULL,'单','仅统计 PAID 订单。'),
('UV','独立访客数','SUM','uv',NULL,'人','仅合成数据的互斥品类客群可相加；真实客群须按用户去重。'),
('CVR','转化率','RATIO','paid_orders','uv','比例','SUM(订单数)/SUM(UV)，不是各品类转化率的算术平均；分母为零返回 NULL。'),
('AOV','客单价','RATIO','gmv','paid_orders','元/单','SUM(GMV)/SUM(订单数)，分母为零返回 NULL。');

ALTER TABLE knowledge_document ADD COLUMN metric_code VARCHAR(32) NOT NULL DEFAULT 'GMV';
ALTER TABLE knowledge_document ADD COLUMN layer_code VARCHAR(16) NOT NULL DEFAULT 'L3_RULE';
CREATE INDEX idx_knowledge_metric_layer ON knowledge_document(metric_code,layer_code);
INSERT INTO knowledge_document(id,title,content,version,metric_code,layer_code) VALUES
('mdl-daily','日品类语义模型','analytics_daily 粒度为 stat_date + category，度量为 gmv、paid_orders、uv。只允许在已注册字段上选择维度和指标，时间范围必填。','1','*','L1_MODEL'),
('sql-daily','按日期品类查询示例','先检索口径；为指标与维度构造语义计划，由编译器生成带日期参数、分组与行数限制的只读 SQL。不得粘贴模型生成的任意 SQL 执行。','1','*','L2_SQL'),
('lineage-gmv','支付金额字段血缘','analytics_daily.gmv <- SUM(biz_order.paid_amount)，过滤 biz_order.status=PAID；业务日期来自 biz_order.paid_date。','1','GMV','L4_LINEAGE'),
('rule-cvr','转化率口径','CVR = SUM(paid_orders)/SUM(uv)，分母为 0 或数据覆盖不完整时不报告可信转化率。','1','CVR','L3_RULE'),
('rule-aov','客单价口径','AOV = SUM(gmv)/SUM(paid_orders)，分母为 0 时返回未知值，不把 NULL 解释为 0。','1','AOV','L3_RULE'),
('rule-uv','UV 口径限制','合成数据的三类客群互斥，才可跨品类相加 UV；真实流量需要用户级去重。','1','UV','L3_RULE'),
('rule-orders','订单数口径','ORDERS 为 PAID 订单数，按支付日归属。','1','ORDERS','L3_RULE');

CREATE TABLE semantic_change_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, owner VARCHAR(128) NOT NULL,
  action VARCHAR(32) NOT NULL, target VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
