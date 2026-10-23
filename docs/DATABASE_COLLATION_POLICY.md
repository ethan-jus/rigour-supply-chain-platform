# Platform 数据库排序规则

数据库、业务表及字符列统一使用 `utf8mb4` / `utf8mb4_general_ci`。数字、日期、二进制 UUID 等非字符列不适用排序规则。

## 新库初始化

9 个有迁移的服务分别提供冻结的 `db/bootstrap/B<版本>__general_ci_bootstrap.sql`，覆盖本次发布之前的全部 V 迁移。基线依次执行原有建表、演进和内置配置初始化，只统一字符排序规则，不导入历史业务数据。Gateway、AI 当前没有业务表迁移；AI 的建库默认值也统一。

Flyway locations 同时包含 `db/migration` 和 `db/bootstrap`。空库选择对应 B 基线，此后执行更高版本的 V 迁移；已有迁移记录的库忽略 B 基线，继续验证原 V 文件。B 脚本与 `baseline-on-migrate` 是不同机制，后者仍为 false。生产所有服务的 `spring.flyway.enabled` 保持 false，必须由人工单独安排初始化/升级。Nacos 若覆盖这些配置，也需要保持一致。

原有 340 个 V 文件没有改动，不能为了统一字面值而替换它们或运行 repair。B 基线发布后也不可再生成或修改。未来新增 V 迁移中的显式排序规则只能为 `utf8mb4_general_ci`；为已有表新增字符列时须显式指定，直到存量表转换完成。

补充历史例外：启用校验门禁前已进入 dev、但高于冻结 B 版本的 5 个迁移（BI V22/V23/V24、IAM V120、Order V48），通过清单中的 `legacy_migrations` 固定文件名和 SHA-256。它们不得删除或改写，不能通过重新生成 B 基线消除差异；其排序规则按下方手动转换流程处理。空库执行 B 后仍会执行这些 V 文件，因此涉及的表也需要检查。此例外不适用于后续新增迁移。

IAM 新增独立的 `B120__general_ci_bootstrap.sql`，将截至 V120 的历史初始化内容统一为 general-ci，供新空库选择；同时给仅指定 `CHARSET=utf8mb4` 的历史建表补上显式 COLLATE，避免 MySQL 字符集默认值覆盖库默认值，并固定建视图时字符串常量的连接排序规则。权限视图的 `ACTIVE` 常量同时显式指定 COLLATE，保证其他连接使用不同默认值时仍可比较。清单仅为新 B120 启用 `explicit_charset_collation`，旧基线仍按原生成规则校验。原 B118、V120 及其校验值全部保留；已有 V 历史的库忽略新 B，仍按原 V 链升级。清单按基线保留独立冻结记录，因此同一个服务可以有多个版本的 B 文件。此修复不自动修复此前已失败的半初始化数据库，也不转换现有库的排序规则。

本地校验冻结内容与后续迁移规则：

```bash
python3 scripts/database/prepare-general-ci-baselines.py --check
```

Flyway B 基线机制说明：[官方文档](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/baseline-migrations)。基线仍使用原项目的 MySQL SQL 方言；换排序规则不等于兼容 MySQL 5.7 或 MariaDB。

## 本次 Linux Integration 初始化

更新 `integration-server.jar` 和 `scripts/integration/InitializeIntegrationDatabase.java`。必须从新 JAR 同时提取 `BOOT-INF/lib` 和整个 `BOOT-INF/classes/db`，不可只提取旧的 migration 目录。以下操作在服务器 `/root/rigour_scm` 下执行；先确认并停止 Integration，避免后台工作线程操作数据库。

```bash
cd /root/rigour_scm
mkdir -p integration-general-ci-init
cd integration-general-ci-init
/usr/local/jdk21/bin/jar xf ../integration-server.jar BOOT-INF/lib BOOT-INF/classes/db
cd ..
/usr/local/jdk21/bin/java \
  --class-path 'integration-general-ci-init/BOOT-INF/lib/*' \
  InitializeIntegrationDatabase.java \
  train integration-general-ci-init/BOOT-INF/classes/db/migration
```

工具先验证本机 MySQL:3306、主机名、排序规则和 `UUID_TO_BIN`，再确认 `rigour_integration` 没有任何表；非空或部分失败的库会拒绝继续，不自动清理或 repair。随后只执行 B27，验证 Flyway 历史、库/表/字符列排序规则和业务表为空。保留 6 个内置飞书模板、2 个模板依赖和 Flyway 历史。若数据库能力检查失败，记录版本与完整错误，先解决兼容性问题，不开启服务自动迁移。

## 已有数据库转换

本次代码修改不会自动转换现有生产/DEV 数据库。修改库默认值也不会改变已有表和列。先对目标服务器运行只读清单，输出包含实际差异、唯一索引冲突检查 SQL、字符外键依赖和建议 DDL：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p \
  < review-general-ci-conversion.sql
```

仓库脚本位于 `scripts/database/review-general-ci-conversion.sql`。它只查询信息并生成 SQL 文本，不执行转换。不要把生成结果直接管道输入 mysql。

维护步骤：

1. 确认服务器和各 Schema，准备可恢复备份并停止写入；本机没有 mysqldump 时使用现有数据库备份设施，不能跳过备份。
2. 执行生成的所有唯一索引冲突查询，必须均无结果。含表达式的唯一索引需要单独审查。保存外键相关表的 SHOW CREATE TABLE，按依赖制定转换和重建约束顺序，不盲目关闭 foreign_key_checks。
3. 审查建议 DDL 的表重建耗时、字段类型和索引长度；字符集转换有时会扩展 TEXT/VARCHAR 类型。确认后在维护窗口逐库执行。
4. 重跑清单，非 general-ci 的库/表/字符列查询应为空；Flyway validate 应通过，再恢复服务。

`general_ci` 不区分大小写，比较语义也与其他排序规则不同。尤其原 Foundation 的 `business_dictionary.value`、Integration 的 `integration_dictionary_source_mapping.source_value`、Sales 的临时拜访员工/客户编码曾使用 `utf8mb4_bin`，转换后仅大小写不同的值可能相等。冲突检查可保护唯一约束，但业务上的大小写语义仍需验收。

## 2026-10-23 新库基线补齐

在 MySQL 8.4 默认 `utf8mb4_0900_ai_ci` 连接下，隔离验收发现旧 HR、ERP、Order、Sales 基线的 CAST / JSON_TABLE / UNION 比较及 Foundation、Integration 的部分表或视图列未完全采用 general-ci。旧 B 和 V 文件保持原字节及校验值，新增以下基线供空库选择：

| 服务 | 新基线 | 冻结的 V 范围 |
| --- | --- | --- |
| ERP | B40 | V1–V40 |
| Foundation | B44 | V1–V44 |
| HR | B25 | V1–V25 |
| Integration | B27 | V1–V27 |
| Order | B57 | V1–V57 |
| Sales | B27.1 | V1–V27 |

这些新文件使用现有生成器的 `explicit_charset_collation=true`，明确连接、表和视图表达式的 general-ci。Sales 的 B27 已冻结，因此新基线使用 B27.1；它不是新增的 V 迁移，未来 V28 仍会正常执行。manifest 保留原 10 项并增加 6 项，`prepare-general-ci-baselines.py --check` 继续逐项校验全部原 B/V 指纹。 Integration 的新 B27 另启用 `normalize_ascii_character_columns=true`，将规范化 SHA-256 十六进制摘要列统一为 utf8mb4/general；默认关闭此选项以保护旧文件，原有二进制采集 ID 主键及可重复采集同一摘要的行为不变。

空库使用该域版本最高的 B；已有 V 历史的库忽略所有 B，继续原 V 升级，不导入基线、不转换原业务表、不替换历史。隔离验收分别覆盖九域当前 B 的初始化、校验和重复启动，以及这六域从旧 V 截止版本升级时历史 checksum、原业务哨兵数据和重复执行保持不变。测试只创建并销毁自身的 MySQL 容器，不连接共享 DEV。

已因旧基线初始化失败的半成品库不由此自动恢复，不能用清库或 repair 绕过；部署前按原有新库/存量库流程判断并处理。
