-- 先启动 mysql profile 应用完成 Flyway，再由数据库管理员执行。
-- reader 只允许读聚合视图，不能读订单明细、任务或用户信息。
GRANT SELECT ON ecom_agent.analytics_daily TO 'agent_reader'@'%';

