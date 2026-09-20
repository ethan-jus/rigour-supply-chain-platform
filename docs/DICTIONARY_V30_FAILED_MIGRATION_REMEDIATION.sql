-- ============================================================================
-- rigour_settings：V30 失败迁移的补救脚本（人工执行，不由 Flyway 自动运行）
-- ============================================================================
--
-- 背景（2026-09-18 诊断）：
--   2026-09-18 13:41:42，V30__dictionary_canonical_aliases.sql 执行到第二个
--   ALTER TABLE 时失败，Flyway 记为 success=0。此后 foundation 服务无法启动：
--       Validate failed: Detected failed migration to version 30
--   V31 / V32 / V33 因此全部未应用。
--
-- 已提交的部分（MySQL DDL 不能回滚，这些已经在库里）：
--   * data_dictionary_item 增加 canonical_dictionary_code / canonical_item_code
--   * 外键 fk_dictionary_canonical_item、检查约束 ck_dictionary_canonical_pair
--   * 33 条历史别名 UPDATE
--
-- 失败的部分（本脚本负责补完）：
--   * standard_parent_key / standard_name_key 生成列
--   * 唯一键 uk_dictionary_standard_name
--   * 表 data_dictionary_merge_log
--
-- 失败原因：
--   唯一键 uk_dictionary_standard_name 要求"同字典 + 同父级 + 同名且未标记历史别名"
--   的标准项唯一，但现存 4 组重名。这 4 条是后来通过字典治理页面新增的——而
--   那个唯一键本来正是用来阻止这种重名的，它没建成，防护一直不在。
--
-- 为什么不能让 Flyway 重跑 V30：
--   V30 用的是 ADD COLUMN（非 IF NOT EXISTS），删掉失败记录后重跑会撞
--   Duplicate column name。而且即便回滚 DDL 再重跑也没用——V30 的 UPDATE
--   清单里没有这 4 条，唯一键照样建不起来。
--
-- 执行前提（务必逐条确认）：
--   1. 已备份 rigour_settings：
--        mysqldump -h192.168.12.7 -P13306 -uroot -p \
--          --single-transaction --routines --triggers \
--          rigour_settings > rigour_settings_before_v30_fix_$(date +%Y%m%d%H%M%S).sql
--   2. 确认没有其他人正在启动 foundation（避免并发写同一张表）。
--   3. 执行者必须是该 Schema 的迁移负责人；本脚本要留档。
--   4. MySQL 的 ALTER TABLE 不能回滚，执行前先跑 STEP 0 的核对查询。
--
-- 执行顺序：STEP 0 → STEP 1 → STEP 2 → STEP 3 → 重启 foundation
-- ============================================================================
--
-- 【执行记录】2026-09-18：STEP 0～STEP 3 已在 DEV 库执行完成。
--   执行前备份：/tmp/rigour_settings_before_v30_fix_20260918164740.sql（109K，3 张表）
--   执行结果：别名 33 → 37；剩余重名 0 组；V30 success=1；
--             standard 列 2、唯一键 1、审计表 1 全部落地；
--             UPDATE data_dictionary 影响 11 行（与治理文档"涉及 11 个字典"一致）。
--   后续：重启 foundation 观察 V31 / V32 / V33。
--   V31 / V32 / V33 已扫描：除本次修复的 4 组重名外无其它阻塞点。
--   V33 会 DROP INDEX uk_dictionary_standard_name 并改建
--   uk_dictionary_tenant_standard_name(tenant_key,dictionary_code,standard_parent_key,standard_name_key)，
--   因此 V30 这个唯一键是过渡性的——但重复数据同样会卡住 V33，故本次修复是必需项。
-- ============================================================================

-- 本脚本只操作业务设置库；显式指定以免跑错库。
USE rigour_settings;


-- ============================================================================
-- STEP 0：执行前核对（只读，预期结果写在每条注释里）
-- ============================================================================

-- 0.1 确认 V30 确实是失败状态，且 V31+ 未应用
SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history
WHERE version >= 30 ORDER BY installed_rank;
-- 预期：只有一行 version=30 success=0；没有 31/32/33

-- 0.2 确认第一个 ALTER 已提交、第二个未提交
SELECT
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema='rigour_settings' AND table_name='data_dictionary_item'
        AND column_name IN ('canonical_dictionary_code','canonical_item_code'))   AS 已建_canonical列,
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema='rigour_settings' AND table_name='data_dictionary_item'
        AND column_name IN ('standard_parent_key','standard_name_key'))           AS 已建_standard列,
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema='rigour_settings' AND table_name='data_dictionary_item'
        AND index_name='uk_dictionary_standard_name')                             AS 已建_唯一键,
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema='rigour_settings' AND table_name='data_dictionary_merge_log') AS 已建_审计表;
-- 预期：2 / 0 / 0 / 0

-- 0.3 确认重名就是这 4 组（唯一键的冲突源）
SELECT dictionary_code, COALESCE(parent_dictionary_item_code,'') AS parent,
       TRIM(dictionary_item_name) AS name, COUNT(*) AS 重复数
FROM data_dictionary_item
WHERE deleted=0 AND canonical_item_code IS NULL
GROUP BY dictionary_code, parent, name HAVING COUNT(*) > 1;
-- 预期：DHB_ORDER_STATUS 待出库/待发货/已完成 各 2；DHB_UNIT 件 2

-- 0.4 确认要保留的标准项与要降级为历史别名的项（共 8 行）
SELECT id, dictionary_code, dictionary_item_code, dictionary_item_name, ordinal, created_by
FROM data_dictionary_item
WHERE deleted=0 AND canonical_item_code IS NULL
  AND ((dictionary_code='DHB_ORDER_STATUS'
        AND dictionary_item_code IN ('FINISHED','COMPLETED','STOCK_UP','PENDING_OUTBOUND','SHIPPED','PENDING_SHIPPED'))
    OR (dictionary_code='DHB_UNIT' AND dictionary_item_name='件'))
ORDER BY dictionary_code, dictionary_item_name, ordinal;
-- 预期 8 行。保留 SYSTEM 创建的那条（ordinal 较小），降级用户创建的那条。


-- ============================================================================
-- STEP 1：解决 4 组重名（口径：保留 SYSTEM 标准项，把后加的降为历史别名）
-- ============================================================================
-- 与 V30 既有 33 条 UPDATE 的写法完全一致：JOIN 标准项、带 IS NULL 守卫、
-- 递增 revision 并记审计时间。这些是 DML，可以包在事务里。

START TRANSACTION;

-- 1.1 已完成：保留 FINISHED，降级 COMPLETED
UPDATE data_dictionary_item old_item
JOIN data_dictionary_item standard
  ON standard.dictionary_code='DHB_ORDER_STATUS' AND standard.dictionary_item_code='FINISHED'
 AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code,
    old_item.canonical_item_code=standard.dictionary_item_code,
    old_item.revision=old_item.revision+1,
    old_item.updated_by='SYSTEM',
    old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_ORDER_STATUS' AND old_item.dictionary_item_code='COMPLETED'
  AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

-- 1.2 待出库：保留 STOCK_UP，降级 PENDING_OUTBOUND
UPDATE data_dictionary_item old_item
JOIN data_dictionary_item standard
  ON standard.dictionary_code='DHB_ORDER_STATUS' AND standard.dictionary_item_code='STOCK_UP'
 AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code,
    old_item.canonical_item_code=standard.dictionary_item_code,
    old_item.revision=old_item.revision+1,
    old_item.updated_by='SYSTEM',
    old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_ORDER_STATUS' AND old_item.dictionary_item_code='PENDING_OUTBOUND'
  AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

-- 1.3 待发货：保留 SHIPPED，降级 PENDING_SHIPPED
UPDATE data_dictionary_item old_item
JOIN data_dictionary_item standard
  ON standard.dictionary_code='DHB_ORDER_STATUS' AND standard.dictionary_item_code='SHIPPED'
 AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code,
    old_item.canonical_item_code=standard.dictionary_item_code,
    old_item.revision=old_item.revision+1,
    old_item.updated_by='SYSTEM',
    old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_ORDER_STATUS' AND old_item.dictionary_item_code='PENDING_SHIPPED'
  AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

-- 1.4 件：保留 PIECE，降级 AUTO_07BD6F29FFF0D21901557C8D249CADCDA83CBD63223D8
UPDATE data_dictionary_item old_item
JOIN data_dictionary_item standard
  ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='PIECE'
 AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code,
    old_item.canonical_item_code=standard.dictionary_item_code,
    old_item.revision=old_item.revision+1,
    old_item.updated_by='SYSTEM',
    old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT'
  AND old_item.dictionary_item_code='AUTO_07BD6F29FFF0D21901557C8D249CADCDA83CBD63223D8'
  AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

-- 1.5 提交前确认：4 条降级项的别名都已写入。不是 4 就 ROLLBACK 重新核对。
SELECT COUNT(*) AS 已写别名的降级项 FROM data_dictionary_item
WHERE canonical_item_code IS NOT NULL
  AND dictionary_item_code IN ('COMPLETED','PENDING_OUTBOUND','PENDING_SHIPPED',
                               'AUTO_07BD6F29FFF0D21901557C8D249CADCDA83CBD63223D8');
-- 预期：4

COMMIT;

-- 1.6 提交后复核：重名应为 0 组，历史别名应为 37 条
SELECT COUNT(*) AS 剩余重名组数 FROM (
  SELECT dictionary_code, COALESCE(parent_dictionary_item_code,'') p, TRIM(dictionary_item_name) n
  FROM data_dictionary_item
  WHERE deleted=0 AND canonical_item_code IS NULL
  GROUP BY dictionary_code, p, n HAVING COUNT(*) > 1
) t;
-- 预期：0

SELECT COUNT(*) AS 历史别名条数 FROM data_dictionary_item WHERE canonical_item_code IS NOT NULL;
-- 预期：37（V30 原有 33 + 本次 4）


-- ============================================================================
-- STEP 2：补完 V30 未执行的三段（与 V30 原文逐字一致）
-- ============================================================================
-- 注意：ALTER TABLE 在 MySQL 里隐式提交，不能回滚。执行前请确认 STEP 1 已提交。

-- 2.1 V30 原文第 142-146 行
ALTER TABLE data_dictionary_item
    ADD COLUMN standard_parent_key VARCHAR(50) GENERATED ALWAYS AS (COALESCE(parent_dictionary_item_code, '')) STORED,
    ADD COLUMN standard_name_key VARCHAR(100) GENERATED ALWAYS AS
        (CASE WHEN deleted=0 AND canonical_item_code IS NULL THEN TRIM(dictionary_item_name) ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_dictionary_standard_name (dictionary_code, standard_parent_key, standard_name_key);

-- 2.2 V30 原文第 148-149 行
UPDATE data_dictionary d SET revision=revision+1, updated_by='SYSTEM', updated_time=UTC_TIMESTAMP()
WHERE EXISTS (SELECT 1 FROM data_dictionary_item i WHERE i.dictionary_code=d.dictionary_code AND i.canonical_item_code IS NOT NULL);

-- 2.3 V30 原文第 151-163 行
CREATE TABLE data_dictionary_merge_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL COMMENT '操作租户审计上下文',
    dictionary_code VARCHAR(50) NOT NULL,
    source_item_code VARCHAR(50) NOT NULL,
    target_item_code VARCHAR(50) NOT NULL,
    source_revision INT NOT NULL,
    target_revision INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_dictionary_merge_tenant (tenant_id, dictionary_code, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典合并审计';

-- 2.4 复核：三段都已落地
SELECT
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema='rigour_settings' AND table_name='data_dictionary_item'
        AND column_name IN ('standard_parent_key','standard_name_key'))  AS standard列,
    (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
      WHERE table_schema='rigour_settings' AND table_name='data_dictionary_item'
        AND index_name='uk_dictionary_standard_name')                    AS 唯一键,
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema='rigour_settings' AND table_name='data_dictionary_merge_log') AS 审计表;
-- 预期：2 / 1 / 1
-- 注意：唯一键那项必须用 COUNT(DISTINCT index_name)。statistics 是索引的每一列一行，
-- 该键有 3 列，直接 COUNT(*) 会得到 3 而不是 1。


-- ============================================================================
-- STEP 3：把 V30 在 Flyway 历史里标记为成功
-- ============================================================================
-- 这是"代 Flyway 留痕"，不是常规操作：V30 的 DDL 已由人工补齐，
-- 若把失败行删除，Flyway 会重跑 V30 并在第一个 ALTER 上撞 Duplicate column。
-- 只在 STEP 2 复核通过后执行，并且只执行一次。

UPDATE flyway_schema_history SET success=1
WHERE version='30' AND description='dictionary canonical aliases' AND success=0;
-- 预期：Rows matched: 1  Changed: 1

-- 3.1 复核
SELECT installed_rank, version, description, success FROM flyway_schema_history
WHERE version >= 30 ORDER BY installed_rank;
-- 预期：version=30 success=1


-- ============================================================================
-- STEP 4：重启 foundation，观察 V31 / V32 / V33 是否顺利应用
-- ============================================================================
-- 重启后核对：
--   SELECT version, description, success FROM flyway_schema_history
--   WHERE version >= 30 ORDER BY installed_rank;
-- 预期：30 / 31 / 32 / 33 全部 success=1
--
-- 若 31/32/33 中任何一条失败，重复本脚本的分析路径：先用 STEP 0 的思路确认
-- 它卡在哪一句、失败前置条件是什么，再决定补救方式；不要在没定位前手工改历史表。
--
-- 完成后删除本文件以外的临时备份确认信息，备份文件本身按保留策略归档，不进入 Git。
