# Integration 集成迁移服务：职责、入口与共享 DEV 运行手册

本文说明 `rigour-integration-migration-service` 在当前一期架构中的边界、供应链入口、数据库初始化和本机启动方式。

## 1. 先区分两个入口

门户里有两个名称相近但职责不同的入口：

| 门户入口 | 当前行为 | 是否经过 Integration |
|---|---|---|
| 订货宝商城系统 | 外部应用卡片，跳转 `https://pc.dhb168.com`；是否免密取决于订货宝是否提供 OIDC、SAML 或一次性登录协议 | 否 |
| 供应链系统 → 订货宝数据同步 | 本平台的内部管理页面，用于维护连接配置、同步任务、字段映射、同步日志和订单镜像 | 是 |

因此，“进入订货宝商城”和“把订货宝数据同步到本平台”不是同一个功能。Integration 不应该把员工浏览器的登录密码转交给订货宝，也不应该让门户前端直接调用订货宝 API。

## 2. Integration 在当前设计中的作用

Integration 是外部系统和本平台领域服务之间的隔离层，负责：

1. 保存每个租户的订货宝连接配置和 Secret 引用（不保存明文密码、API Key 或 Token）。
2. 根据订货宝确认的 API 合同处理认证、分页、时间窗口、限流、重试和幂等。
3. 先把外部原始响应写入 Raw Landing，再转换成可追踪的内部镜像或归一化事件。
4. 保存每一次同步批次、增量游标、失败死信和人工重放记录。
5. 输出内部 Outbox 事件给订单、ERP、BI 等领域服务；下游服务不直接读取第三方接口。
6. 记录外部与内部事实的差异，并记录每个租户、业务域当前由谁拥有事实主权。

当前代码已经提供控制面接口和数据库迁移，允许在门户中配置连接、任务和字段映射；订货宝正式 API 地址和账号 Secret 必须来自供应商/负责人确认，不能用测试账号密码臆造真实联调。
当前版本已经在 Integration 内实现订货宝 `DhbClient`、HTTP 适配器、商品/订单公开查询、订单明细读取和订单 Worker（认证、超时、有限重试、进程内限流、偏移分页、更新时间窗口、Raw Landing、订单镜像、Outbox 和 checkpoint）；`integration-migration-api` 已提供版本化跨服务契约。每个连接器的默认 `ORDER` 任务由 Integration 自动创建，历史启用连接器由 Flyway V4 补齐。Order Center 已实现通过内部可信服务身份动态发现启用订单任务、自动拉取订单及关联明细并完成本地幂等落库；客户、仓库、员工目录、死信重放和下游 Outbox 消费仍需后续实现。

## 3. 从供应链入口调用的完整链路

```text
浏览器 Portal :5100
  └─ 供应链系统 → 订货宝数据同步
      └─ /api/v1/integration/dhb/**
          └─ Gateway :26880
              ├─ 校验 IAM Token
              ├─ 生成带 tenantId/userId/permission 的签名上下文
              └─ 转发到 Integration :26882
                  ├─ 再验证上下文和租户权限
                  └─ 访问 rigour_integration（不访问 IAM/业务库）
```

现有 API：

- `GET/POST/PUT /api/v1/integration/dhb/connectors`
- `POST /api/v1/integration/dhb/connectors/{id}/test`（只验证 Secret，不返回 token）
- `GET/POST/PUT /api/v1/integration/dhb/sync-tasks`
- `GET /api/v1/integration/dhb/orders/mirrors`
- `GET /api/v1/integration/dhb/sync-logs`
- `POST /api/v1/integration/dhb/products/{connectorId}/query`
- `POST /api/v1/integration/dhb/orders/{connectorId}/query`
- `POST /api/v1/integration/dhb/orders/{connectorId}/{orderNumber}/content`
- `POST /api/v1/integration/dhb/orders/sync-tasks/{taskId}/run`
- `GET /api/v1/integration/dhb/connectors/{connectorId}/field-mappings`
- `POST/PUT /api/v1/integration/dhb/field-mappings`

这些接口要求租户上下文和 `integration:dhb:read/write` 权限。租户 ID 取自 Gateway 签名上下文，不接受前端请求体自行指定的租户 ID。

## 4. 数据库表分层

Flyway `V1` 建立连接器、同步任务、Raw Landing、订单镜像、同步日志和字段映射；`V2` 补齐运行控制面；`V3` 将连接表统一为 `integration_dhb_connector`：

| 表 | 作用 |
|---|---|
| `integration_dhb_connector` | 租户级连接配置、API 版本、凭据引用和最近探活状态 |
| `integration_sync_task` | 对象同步任务、手动/定时策略、批量大小和重试上限 |
| `integration_raw_landing` | 外部原始响应及版本、来源更新时间、处理结果；支持同一业务对象多次修订 |
| `integration_sync_run` | 一次同步批次的窗口、游标和 fetched/accepted/duplicate/rejected 计数 |
| `integration_sync_checkpoint` | 每个租户任务的增量游标；只有成功批次才能推进 |
| `integration_dead_letter` | 处理失败记录和可重放队列，不复制 Raw Payload |
| `integration_order_mirror` | 订货宝订单只读镜像，不取代订单中心的业务主权 |
| `integration_outbox_event` | 归一化内部事件，供订单、ERP、BI 异步消费 |
| `integration_reconciliation_case` | 外部/内部事实差异和处理状态 |
| `integration_domain_ownership` | 记录租户业务域的外部主导、影子校验或内部主导状态 |
| `integration_sync_log` / `integration_field_mapping` | 诊断日志和源字段到内部字段的配置 |

不要在迁移里插入某个租户的订货宝账号、密码或测试数据。租户、连接器和任务应由授权管理员从供应链入口创建；需要测试数据时，使用共享 DEV 数据库中明确标记的测试租户或通过真实 API 拉取。

## 5. 一次性创建共享 DEV 数据库和账号

下面的 SQL 由具备共享 DEV MySQL 管理权限的人在 Navicat 执行一次。`<...>` 是本机生成后临时替换的值，不要提交到 Git、Nacos 或聊天记录。

先在安全终端生成两个不同的密码（只显示一次并保存到团队 Secret 管理工具）：

```bash
openssl rand -hex 32   # 复制为 INTEGRATION_DB_APP_PASSWORD
openssl rand -hex 32   # 复制为 INTEGRATION_DB_MIGRATOR_PASSWORD
```

然后执行：

```sql
CREATE DATABASE IF NOT EXISTS rigour_integration
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'rigour_integration_app'@'%'
  IDENTIFIED BY '<INTEGRATION_DB_APP_PASSWORD>';
CREATE USER IF NOT EXISTS 'rigour_integration_migrator'@'%'
  IDENTIFIED BY '<INTEGRATION_DB_MIGRATOR_PASSWORD>';

-- 运行时账号：只允许读写业务表，不允许改表结构。
GRANT SELECT, INSERT, UPDATE, DELETE
  ON rigour_integration.* TO 'rigour_integration_app'@'%';

-- Flyway账号：只用于启动迁移，不用于应用请求。
-- V3 使用 RENAME TABLE；MySQL 要求旧表具备 DROP，故迁移账号必须包含 DROP。
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
  ON rigour_integration.* TO 'rigour_integration_migrator'@'%';

FLUSH PRIVILEGES;
```

如果共享 DEV 已经执行过 V1/V2，但启动日志出现
`DROP command denied ... integration_dinghuobao_connector`，只需由数据库管理员补授本库迁移账号的
`DROP` 权限，然后重启 Integration，让 Flyway 重新执行尚未成功的 V3：

```sql
GRANT DROP ON rigour_integration.*
  TO 'rigour_integration_migrator'@'%';
FLUSH PRIVILEGES;
```

不要手工删除或修改 `flyway_schema_history`。如果补权后日志明确提示存在失败迁移记录，先在平台仓库根目录使用同一迁移账号执行一次 Flyway `repair`，再重新启动。

本机安装 Docker/OrbStack 时，可以用 Flyway 官方镜像执行 Repair。密码只在本机终端输入，不要提交或发到聊天中：

注意：IDEA Run Configuration 中的环境变量只注入 IDEA 启动的 Java 进程，不会自动传给
Terminal 或 Docker 容器。下面读取的是同一个 INTEGRATION_DB_MIGRATOR_PASSWORD，不是新密码；
只是把它临时传给 Flyway 容器。

```bash
read -r -s "INTEGRATION_DB_MIGRATOR_PASSWORD?请输入 rigour_integration_migrator 数据库密码（输入时不显示）： "
printf '\n'
if [[ -z "$INTEGRATION_DB_MIGRATOR_PASSWORD" ]]; then
  echo "未输入数据库密码，已停止；没有执行 Docker Repair。"
else
  export FLYWAY_PASSWORD="$INTEGRATION_DB_MIGRATOR_PASSWORD"
  export DHB_MIGRATION_DIR="$PWD/services/rigour-integration-migration-service/integration-migration-service/src/main/resources/db/migration"

  docker run --rm \
    -v "$DHB_MIGRATION_DIR:/flyway/sql:ro" \
    -e FLYWAY_URL='jdbc:mysql://82.157.4.176:13306/rigour_integration?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true' \
    -e FLYWAY_USER='rigour_integration_migrator' \
    -e FLYWAY_PASSWORD \
    -e FLYWAY_LOCATIONS='filesystem:/flyway/sql' \
    flyway/flyway:11.14.1 repair

  unset FLYWAY_PASSWORD DHB_MIGRATION_DIR
fi
unset INTEGRATION_DB_MIGRATOR_PASSWORD
```

Repair 成功后重新启动 IDEA 中的 Integration；Flyway 应继续执行 V3，将连接表改名为
`integration_dhb_connector`。如果 Repair 报 `DROP command denied`，先重新执行上面的迁移账号授权；如果报连接或密码错误，只检查本机终端输入，不要修改迁移文件。

如果账号已经存在但需要轮换密码，另执行（不要把旧密码写入文档）：

```sql
ALTER USER 'rigour_integration_app'@'%' IDENTIFIED BY '<new app password>';
ALTER USER 'rigour_integration_migrator'@'%' IDENTIFIED BY '<new migrator password>';
FLUSH PRIVILEGES;
```

共享服务器应通过防火墙限制来源；如果数据库管理员已经知道服务主机网段，把 `'%'` 换成明确的主机或网段账号。账号不能复用 `root`，也不能让运行时应用使用迁移账号。

执行后检查：

```sql
SHOW GRANTS FOR 'rigour_integration_app'@'%';
SHOW GRANTS FOR 'rigour_integration_migrator'@'%';
```

## 6. Nacos 和本机 Secret 配置

Nacos Namespace 使用共享 DEV Namespace，Data ID 为：

```text
rigour-integration-migration-service.yaml
```

Group 使用 `DEFAULT_GROUP`。非敏感的远端 JDBC URL、用户名和连接池参数可以写在 Nacos；密码和 HMAC 上下文密钥使用占位符，由每位开发者的 IDEA Secret/本机 Secret 文件注入：

```text
INTEGRATION_DB_APP_PASSWORD=<刚生成的运行时账号密码>
INTEGRATION_DB_MIGRATOR_PASSWORD=<刚生成的迁移账号密码>
RIGOUR_CONTEXT_TRUST_KEY_V1=<与Gateway、IAM、其他领域服务相同的DEV HMAC密钥>
NACOS_USERNAME=nacos
NACOS_PASSWORD=<Nacos密码>
# 订货宝账号密码由 Secret 引用解析，不写 Nacos；开发默认引用 env://RIGOUR_DHB_DEV
RIGOUR_DHB_DEV_SERIAL_NUMBER=<订货宝接口账号>
RIGOUR_DHB_DEV_PASSWORD=<订货宝接口密码>
```

同一共享 DEV 数据库的开发者必须使用相同的 `RIGOUR_CONTEXT_TRUST_KEY_V1`；数据库密码只需一致到对应数据库账号，不要把第三方订货宝账号密码放进这里。Nacos 示例见 `docs/nacos/rigour-integration-migration-service.example.yml`。

`dev,local` 的含义是：`dev` 读取远端共享 Nacos/数据库，`local` 只覆盖本机运行行为（默认不注册服务发现），不是“dev 数据库 + local 数据库”。

## 7. 启动服务

先确认上面的数据库账号和 Nacos 配置已完成，再在平台仓库执行：

```bash
cd "/Users/ethan/myspance/rigour/B2B供应链/自动化系统构建/06_代码工程/rigour-supply-chain-platform"

./mvnw -B -pl services/rigour-integration-migration-service/integration-migration-service \
  -am -DskipTests install

SPRING_PROFILES_ACTIVE=dev,local \
./mvnw -f services/rigour-integration-migration-service/integration-migration-service/pom.xml \
  spring-boot:run
```

这里故意分成两条命令：第一条把共享模块和 `integration-migration-api` 安装到本机 Maven 仓库；第二条从服务自己的 POM 运行。直接在根聚合 POM 上执行 `spring-boot:run` 会因为根项目没有主类而失败。

也可以在 IDEA 直接运行主类：

```text
com.rigour.integration.IntegrationMigrationServiceApplication
Active profiles: dev,local
VM options: -Dspring.output.ansi.enabled=ALWAYS
```

启动成功标志是控制台出现带服务名、端口 `26882` 的 `✅✅✅` 日志。Flyway 会自动执行 V1、V2、V3、V4；V4 只为已有启用连接器补齐默认订单任务。服务不会自动创建第三方连接器，也不会自动调用订货宝。

## 8. 启动后验收

```bash
curl -fsS http://localhost:26882/actuator/health
```

在 Navicat 检查：

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'rigour_integration'
  AND table_name LIKE 'integration\\_%'
ORDER BY table_name;
```

然后登录 Portal，进入“供应链系统 → 订货宝数据同步”：

1. 在“连接配置”创建连接器，只填写订货宝确认的完整 API URL 和 Secret 引用，例如 `env://RIGOUR_DHB_DEV`；运行时从 `RIGOUR_DHB_DEV_SERIAL_NUMBER` 和 `RIGOUR_DHB_DEV_PASSWORD` 读取，`application-local.yml` 不保存凭据。创建后检查“同步任务”中是否出现 `DHB_ORDER_DEFAULT`。
2. 订单任务初期保持 `IDLE`，由 Order Center 的定时调度器根据连接器状态、任务启用状态和任务状态发现；也可以从订单任务执行“立即同步”。
3. 需要扩展其他对象类型时，再在“同步任务”创建扩展任务；当前尚未接入对应领域消费者的扩展任务不提供“立即同步”入口。
4. 配置字段映射，检查同步日志、Raw Landing 和订单镜像。
5. 定时同步、死信重放和下游订单/ERP/BI消费完成前，页面上的“已同步数量”不能伪造成功数量。连接测试或同步返回 `DHB_SECRET_NOT_CONFIGURED` 时不会调用第三方。

Integration 未启动只会让 `/api/v1/integration/**` 连接失败；它不应让 IAM 登录、系统管理或供应链菜单整体跳回登录页。若出现这种现象，应沿 Portal → Gateway → Integration 的 `requestId` 分别查看日志。
