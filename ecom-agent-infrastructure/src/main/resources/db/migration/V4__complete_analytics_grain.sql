-- Build the read model from every paid-order or traffic grain. Starting only
-- from traffic rows would silently discard paid orders with missing traffic.
-- Missing UV stays NULL: an unknown denominator must never become a fake zero.
CREATE OR REPLACE VIEW analytics_daily AS
SELECT grain.stat_date, grain.category, COALESCE(o.gmv, 0) AS gmv,
       COALESCE(o.paid_orders, 0) AS paid_orders, t.uv
FROM (
  SELECT stat_date, category FROM traffic_daily
  UNION
  SELECT paid_date AS stat_date, category FROM biz_order WHERE status = 'PAID'
) grain
LEFT JOIN traffic_daily t
  ON grain.stat_date = t.stat_date AND grain.category = t.category
LEFT JOIN (
  SELECT paid_date, category, SUM(paid_amount) AS gmv, COUNT(*) AS paid_orders
  FROM biz_order WHERE status = 'PAID' GROUP BY paid_date, category
) o ON grain.stat_date = o.paid_date AND grain.category = o.category;
