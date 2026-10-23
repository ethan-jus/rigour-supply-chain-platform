# IAM V139 历史兼容与权限前向收敛

## 已核实的历史

2026-10-22 迁移前对当前 DEV IAM 只读核对：143 条成功历史，最高版本 139，无失败记录。V139 为：

| 项目 | 值 |
| --- | --- |
| script | `V139__cost_dashboard_permissions.sql` |
| description | `cost dashboard permissions` |
| type / checksum / success | `SQL` / `-812060856` / `1` |

完整无凭据清单保留在本机 `/tmp/rigour-cost-live-review/iam-history-20261022.tsv`，不提交环境数据。

风险页面 V139 来自 `caf5d6dde476a01df5a8ccbe073d479611ee9e1a`，2026-10-21 已进入远端 dev。本地成本分支于 2026-10-22 11:21 快进到 dev 后出现双 V139。成本 V139 尚未提交 Git，但已在本 DEV 数据库执行，不能重命名或修改以消除冲突。

## 实现

- 保留两份原 V139 的路径与字节；`IamV139MigrationResources` 对文件 SHA256 以及数据库 V139 的脚本、描述、类型、checksum、成功状态和唯一性精确校验。
- 仅选择该数据库确实执行过的一份 V139 供 Flyway 解析。尚无 V139 的数据库选择远端已发布的风险版本。任何未知、失败、重复历史或缺失／变更的原文件立即拒绝。
- 其他 V、冻结 B 基线及全部校验保持有效，不使用 repair、缺失忽略、乱序、自动 baseline 或关闭 validate；未来未知历史同样拒绝。
- Spring 默认 Flyway 初始化器与 `scripts/run-service-flyway-migrations.sh` 共用该类。脚本不再提供违反仓库约定的 repair 动作。IAM 正式迁移工具直接编译同一选择器源码，不依赖本机残留 target 类或临时复制 SQL。
- 唯一新增 `V141__iam_converge_cost_and_payment_risk_permissions.sql`，补齐成本看板读权限／独立导出动作和风险页面读权限。INSERT 有存在性保护；不修改角色授权或数据范围，不自动授予已有角色导出权限。

## DEV 执行结果与启动要求

2026-10-22 18:38 使用正式 `scripts/run-service-flyway-migrations.sh` 及当前 DEV 连接依次执行正常 `migrate → validate → migrate`，全部成功：

| 检查 | 实际结果 |
| --- | --- |
| 第一次 migrate | 从 139 到 141，执行原 V140 与新增 V141 共两条 |
| validate | 成功，保留全部历史校验 |
| 第二次 migrate | 执行 0 条，无待执行迁移 |
| 历史条数 | 143 → 145；迁移前全部 143 条完整字段逐行一致 |
| 原 V139 | 脚本、描述、checksum `-812060856`、成功状态及其他原字段全部不变 |
| 角色资源 / 应用角色授权 / 成员角色范围 | 290 / 573 / 0，三表记录数及完整内容排序后 SHA256 全部不变 |
| 风险页 | `permission_code` 从空值补为 `analytics:dashboard:read` |
| 成本页面 / 独立导出资源 | 既有 `analytics:cost-dashboard:read`、`analytics:cost-dashboard:export` 保持原样，无重复资源 |

没有 repair、关闭校验、修改历史或使用临时迁移副本。日志位于本机 `/tmp/rigour-cost-live-review/iam-v139-dev-{first-migrate,validate,second-migrate}.log`；迁移前后无凭据快照及比对结果为同目录 `iam-v139-before-migrate.tsv`、`iam-v139-after-migrate.tsv`、`iam-v139-dev-migration-check.json`。

重启时应恢复 IAM 默认 `classpath:db/migration,classpath:db/bootstrap`，移除本机临时迁移副本的 locations 覆盖，并保持旧 `allow-missing-v133` 入口关闭。V140、V141 已在 DEV 成功执行，正常启动应只校验、不再执行它们；重启与页面权限验收由主任务统一处理。

不在生产启动开启 Flyway；正式部署仍按项目既定独立迁移安排执行。脚本默认地址不代表当前 DEV，运行时必须显式指定目标连接，密码仅通过环境变量或交互输入传入。

## 本轮验证与边界

- `IamV139MigrationResourcesTest` 16 项隔离测试通过，覆盖两类真实 V139 指纹前向执行、历史不变、空库选择、正常 Spring 初始化器、classpath 与 filesystem、篡改／缺失资源、未知／失败／重复历史、其他历史缺失或变更及未知未来版本拒绝。
- 既有 V133 策略 9 项隔离回归通过；未更改该策略或启用其绕过入口。
- `IamV139ConvergenceMySqlTest` 使用专用 MySQL 8.4 容器覆盖空库、风险历史、成本历史的原版 SQL 与 V141 收敛、历史／角色范围不变和手工重跑幂等。当前无可用 Docker，该项跳过，不能视为 MySQL SQL 已执行通过。
- 正式脚本 `ACTION=info` 已只读访问当前 DEV：当前 139 成本历史，140、141 待执行；无重复版本异常。日志 `/tmp/rigour-cost-live-review/iam-v139-cli-info.log`。
- `bash -n`、冻结基线与历史迁移校验通过。日志 `/tmp/rigour-cost-live-review/iam-v139-final-tests.log`。
- 当前 DEV 的成本 V139 历史已在真实 MySQL 完成正常前向迁移、再次 validate、零执行重跑及历史／角色授权比对。空库和风险 V139 历史的完整原SQL收敛仍只有已编写、因 Docker 不可用而跳过的容器场景，不以当前 DEV 单一路径代替全部兼容矩阵。
- 未重启 IAM；恢复默认 locations 后的服务启动和页面权限验收仍由当前主任务统一执行并补充记录。
