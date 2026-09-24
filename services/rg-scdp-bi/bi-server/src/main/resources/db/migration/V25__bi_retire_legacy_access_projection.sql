-- 看板统一使用 IAM 当前角色的数据范围，删除独立的旧授权投影；历史审计记录保留。
DELETE FROM bi_data_access_scope;
DELETE FROM bi_data_access_identity;
