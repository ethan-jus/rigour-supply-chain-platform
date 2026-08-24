-- V11迁移夹具：任务最初属于A，历史上已有A/B两个连接器，当前任务已改绑到B。
-- connector A即使已软删除也仍是历史来源候选，因此旧Raw/Mirror不能据当前task绑定推断为B。
INSERT INTO integration_dhb_connector
    (id, tenant_id, connector_code, connector_name, status, version,
     created_at, updated_at, deleted_at, delete_reason)
VALUES
    (UUID_TO_BIN('20000000-0000-0000-0000-000000000001'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000001'),
     'HISTORY_A', '历史连接器A', 'DISABLED', 0,
     '2026-01-01 00:00:00.000000', '2026-01-02 00:00:00.000000',
     '2026-01-02 00:00:00.000000', '测试历史改绑'),
    (UUID_TO_BIN('20000000-0000-0000-0000-000000000002'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000001'),
     'HISTORY_B', '当前连接器B', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000000', '2026-01-03 00:00:00.000000', NULL, NULL),
    (UUID_TO_BIN('20000000-0000-0000-0000-000000000003'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000002'),
     'HISTORY_SINGLE', '唯一历史连接器', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000', NULL, NULL);

INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     enabled, version, created_at, updated_at)
VALUES
    (UUID_TO_BIN('30000000-0000-0000-0000-000000000001'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000001'),
     UUID_TO_BIN('20000000-0000-0000-0000-000000000002'),
     'HISTORY_REBOUND', 'ORDER', 'PAUSED', 0, 1,
     '2026-01-01 00:00:00.000000', '2026-01-03 00:00:00.000000');

INSERT INTO integration_sync_run
    (id, tenant_id, task_id, trigger_type, status, version, created_at, updated_at)
VALUES
    (UUID_TO_BIN('40000000-0000-0000-0000-000000000001'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000001'),
     UUID_TO_BIN('30000000-0000-0000-0000-000000000001'),
     'SCHEDULED', 'SUCCEEDED', 0,
     '2026-01-01 01:00:00.000000', '2026-01-01 01:05:00.000000');

INSERT INTO integration_raw_landing
    (id, tenant_id, connector_id, run_id, source_system, source_object_type,
     source_id, payload_json, payload_checksum, received_at, landing_status, version)
VALUES
    (UUID_TO_BIN('50000000-0000-0000-0000-000000000001'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000001'), NULL,
     UUID_TO_BIN('40000000-0000-0000-0000-000000000001'),
     'DHB', 'ORDER', 'HIST-REBOUND', JSON_OBJECT('OrderSN', 'HIST-REBOUND'),
     REPEAT('a', 64), '2026-01-01 01:00:00.000000', 'PROCESSED', 0),
    (UUID_TO_BIN('50000000-0000-0000-0000-000000000002'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000002'), NULL, NULL,
     'DHB', 'ORDER', 'HIST-SINGLE', JSON_OBJECT('OrderSN', 'HIST-SINGLE'),
     REPEAT('b', 64), '2026-01-01 01:00:00.000000', 'PROCESSED', 0);

INSERT INTO integration_order_mirror
    (id, tenant_id, source_order_id, order_no, raw_landing_id, mirror_status,
     version, created_at, updated_at)
VALUES
    (UUID_TO_BIN('60000000-0000-0000-0000-000000000001'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000001'),
     'HIST-REBOUND', 'HIST-REBOUND',
     UUID_TO_BIN('50000000-0000-0000-0000-000000000001'), 'ACTIVE', 0,
     '2026-01-01 01:00:00.000000', '2026-01-01 01:00:00.000000'),
    (UUID_TO_BIN('60000000-0000-0000-0000-000000000002'),
     UUID_TO_BIN('10000000-0000-0000-0000-000000000002'),
     'HIST-SINGLE', 'HIST-SINGLE',
     UUID_TO_BIN('50000000-0000-0000-0000-000000000002'), 'ACTIVE', 0,
     '2026-01-01 01:00:00.000000', '2026-01-01 01:00:00.000000');
