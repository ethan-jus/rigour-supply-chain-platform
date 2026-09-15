# 飞书 CRM 历史创建人修正

## 原因与范围

- 当前 `CrmInternalCustomerService` 已把服务调用的审计身份写为 `SYSTEM`，已有测试覆盖；历史记录仍保留旧飞书导入服务 principal UUID。
- 新增 CRM V15，仅处理飞书来源且创建人为 `nameUUIDFromBytes("rigour-integration-feishu-import-service")` 的记录。关联角色、客户扩展资料按租户和主体匹配来源；人工创建人及其他来源不转换。
- 仅修正创建人。保留创建时间、更新人、更新时间、版本及业务字段；显式赋回 `updated_time`，避免 `crm_customer` 的 ON UPDATE 自动修改历史时间。

## 创建人修正已执行（V15）

- Flyway 从 V14 升至 V15，成功执行 1 个迁移。
- 客户 1,721 条；主体、联系人、客户角色、客户扩展资料各 1,651 条；地区 17 条，共修正 8,342 条记录。客户类型、地址没有符合条件的旧记录。
- 用户反馈的 `CUS202606018483 / 星光台球厅` 创建人已为 `SYSTEM`；创建时间仍为北京时间 `2026-09-08 14:03:46`，更新人和更新时间保留原值。已登录本地 Portal 的客户详情页验证一致。

## 验证及恢复证据

- 根目录 `./mvnw verify` 通过：1,272 项中 198 项跳过，0 失败、0 错误；新增 MySQL 容器迁移测试因无 Docker 跳过，未宣称该用例已运行。
- 真实 MySQL 事务预演执行相同 SQL，逐表比对创建人及其余字段摘要，重复执行验证幂等后回滚；回滚恢复核对通过。Flyway 正式执行后再次核对，相同的 8,342 条创建人已持久化，其余字段摘要保持一致。
- 原审计值备份在本机 `/tmp/crm-feishu-creator-backup-20260915.json`，权限 `0600`，未加入 Git；比对摘要 `/tmp/crm-feishu-creator-check-20260915.json`。迁移日志 `/tmp/crm-feishu-system-creator-migration.log`，验证日志 `/tmp/crm-feishu-system-creator-verify.log`。
- 修改位于 `feature/system-optimization`；迁移、测试和本记录已暂存，未提交推送。Portal 无代码改动。

## 更新人补齐已执行（V16）

- 用户进一步确认系统更新人应为 `SYSTEM`。客户中的 `codex-bi-owner-code-repair` 属于销售编码修复任务；其余关联记录保留旧飞书导入服务 principal。现行服务同步已写 `SYSTEM`，本次新增迁移处理历史数据。
- V16 仅匹配飞书来源的已知系统身份：客户允许旧导入服务和该修复任务，其余七张表仅允许旧导入服务；角色、客户扩展资料继续按租户和主体关联来源。保留人工及其他未识别更新人。
- DEV Flyway 已从 V15 升至 V16。修正客户 1,721 条，主体、联系人、客户角色、客户扩展资料各 1,651 条，地区 17 条，共 8,342 条更新人。
- 真实 MySQL 事务预演、重复执行、回滚核对及正式迁移后核对均通过；逐表对比确认除 `updated_by` 外所有字段摘要一致，包括创建人、创建时间、更新时间、版本及业务字段。
- 已登录 Portal 验证 `CUS202606018483 / 星光台球厅`：创建人和更新人均为 `SYSTEM`，原创建时间 `2026-09-08 14:03:46`、更新时间 `2026-09-10 05:27:21`（北京时间）保持不变。
- 根目录 `./mvnw verify` 通过：1,273 项中 199 项跳过，0 失败、0 错误。新增更新人迁移测试覆盖人工审计、其他来源、租户隔离、时间保护及幂等，因无 Docker 跳过；真实 SQL 已按上面的事务预演和持久化核对验证。
- 本机原审计值备份 `/tmp/crm-feishu-updater-backup-20260915.json`，比对摘要 `/tmp/crm-feishu-updater-check-20260915.json`，均为 `0600`，未加入 Git。迁移日志 `/tmp/crm-feishu-system-updater-migration.log`，构建日志 `/tmp/crm-feishu-system-updater-verify.log`。
- V15 未改写。V16、回归测试及本记录已暂存，未提交推送；无需服务重启，Portal 无代码改动。
