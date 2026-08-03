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

当前代码已经提供控制面接口和数据库迁移，允许在门户中配置连接、任务和字段映射；真正的订货宝拉取 Worker 必须以订货宝确认的 API 地址、版本、签名算法、接口字段和 Secret 为前提，不能用测试账号密码臆造同步实现。

## 3. 从供应链入口调用的完整链路

```text
浏览器 Portal :5100
  └─ 供应链系统 → 订货宝数据同步
      └─ /api/v1/integration/dinghuobao/**
          └─ Gateway :26880
              ├─ 校验 IAM Token
              ├─ 生成带 tenantId/userId/permission 的签名上下文
              └─ 转发到 Integration :26882
                  ├─ 再验证上下文和租户权限
                  └─ 访问 rigour_integration（不访问 IAM/业务库）
```

现有 API：

- `GET/POST/PUT /api/v1/integration/dinghuobao/connectors`
- `GET/POST/PUT /api/v1/integration/dinghuobao/sync-tasks`
- `GET /api/v1/integration/dinghuobao/order-mirrors`
- `GET /api/v1/integration/dinghuobao/sync-logs`
- `GET /api/v1/integration/dinghuobao/{connectorId}/field-mappings`
- `POST/PUT /api/v1/integration/dinghuobao/field-mappings`

这些接口要求租户上下文和 `integration:dinghuobao:read/write` 权限。租户 ID 取自 Gateway 签名上下文，不接受前端请求体自行指定的租户 ID。

## 4. 数据库表分层

Flyway `V1` 建立连接器、同步任务、Raw Landing、订单镜像、同步日志和字段映射；`V2` 补齐运行控制面：

| 表 | 作用 |
|---|---|
| `integration_dinghuobao_connector` | 租户级连接配置、API 版本、凭据引用和最近探活状态 |
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
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON rigour_integration.* TO 'rigour_integration_migrator'@'%';

FLUSH PRIVILEGES;
```

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

启动成功标志是控制台出现带服务名、端口 `26882` 的 `✅✅✅` 日志。Flyway 会自动执行 V1、V2；服务不会自动创建第三方连接器，也不会自动调用订货宝。

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

1. 在“连接配置”创建连接器，只填写订货宝确认的 API Base URL 和 Secret 引用，例如 `secret-ref:dev/dinghuobao/<tenant-code>/main`。
2. 在“同步任务”先创建 `ORDER_PULL`，初期使用“手动”策略，确认 API 合同后再启用定时策略。
3. 配置字段映射，检查同步日志、Raw Landing 和订单镜像。
4. 真实同步 Worker、死信重放和下游订单/BI消费完成前，页面上的“已同步数量”为零是正确的，不应伪造成功数量。

Integration 未启动只会让 `/api/v1/integration/**` 连接失败；它不应让 IAM 登录、系统管理或供应链菜单整体跳回登录页。若出现这种现象，应沿 Portal → Gateway → Integration 的 `requestId` 分别查看日志。
