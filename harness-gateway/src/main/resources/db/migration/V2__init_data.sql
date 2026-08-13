-- 初始化示例规则
INSERT INTO rule_base (rule_id, rule_name, rule_type, category, content, priority, enabled, tags) VALUES
('COMP-001', '严重超时赔付', 'compensation', '超时', '超时30分钟以上，赔付订单金额的50%，最高不超过20元', 100, true, '超时,赔付,严重'),
('COMP-002', '一般超时赔付', 'compensation', '超时', '超时15-30分钟，赔付3-5元优惠券', 90, true, '超时,赔付,一般'),
('COMP-003', '轻微超时赔付', 'compensation', '超时', '超时15分钟以内，致歉并赠送1元红包', 80, true, '超时,赔付,轻微'),
('COMP-004', '餐品洒漏赔付', 'compensation', '餐品问题', '餐品洒漏导致不可食用，全额退款；部分洒漏，赔付50%', 100, true, '餐品,洒漏,赔付'),
('COMP-005', '错单赔付', 'compensation', '错单', '送错订单，优先补送正确订单，同时赔付5元优惠券', 95, true, '错单,赔付'),
('ANA-001', '超时归因-商家出餐慢', 'analysis', '超时归因', '商家出餐时长超过平均值2倍以上，判定商家出餐慢为超时主因', 100, true, '超时,归因,商家'),
('ANA-002', '超时归因-运力不足', 'analysis', '超时归因', '站点运力比超过0.85，骑手平均配送时长超过正常值1.5倍，判定运力不足', 90, true, '超时,归因,运力'),
('ANA-003', '超时归因-天气异常', 'analysis', '超时归因', '当日有暴雨/暴雪/大风预警，全城平均配送时长上升20%以上', 85, true, '超时,归因,天气');

-- 初始化示例评测 Case
INSERT INTO eval_case (case_id, scenario, title, description, input, expected_rule_ids, expected_tool_calls, expert_answer) VALUES
('EC-001', 'abnormal_order_analysis', '标准超时单-商家出餐慢', '商家出餐超时20分钟导致订单整体超时', '{"order_id":"TEST001"}', 'ANA-001', '查询订单,查询ETA,查询运力', '主因为商家出餐慢，出餐时长超过站点平均值2倍'),
('EC-002', 'abnormal_order_analysis', '运力紧张导致超时', '高峰期运力不足导致接单延迟', '{"order_id":"TEST002"}', 'ANA-002', '查询订单,查询ETA,查询运力', '主因为运力不足，站点运力比达0.9'),
('EC-003', 'compensation_suggestion', '严重超时赔付', '超时35分钟用户投诉', '{"order_id":"TEST003","complaint_type":"OVERTIME"}', 'COMP-001', '查询订单,匹配赔付规则', '应赔付，适用严重超时规则，建议赔付17.75元'),
('EC-004', 'compensation_suggestion', '一般超时赔付', '超时20分钟用户投诉', '{"order_id":"TEST004","complaint_type":"OVERTIME"}', 'COMP-002', '查询订单,匹配赔付规则', '应赔付，适用一般超时规则，建议发放5元优惠券');
