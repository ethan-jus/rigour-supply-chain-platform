# 本地开发环境配置

更新日期：2026-09-16。日常开发统一使用台式机 **192.168.12.7** 的基础设施，本机只启动需要调试的服务。

## 三种配置，直接选择

每个启动模块的 `src/main/resources` 保留公共 `application.yml`，按用途选择一个环境：

| Profile | 配置文件 | 用途 |
|---|---|---|
| `dev` | `application-dev.yml` | 日常开发；已填写台式机的数据库、Redis、Nacos等DEV连接信息 |
| `local` | `application-local.yml` | 个人覆盖；自动读取同目录DEV配置，在 `---` 后覆盖自己的端口、数据库或服务地址 |
| `prod` | `application-prod.yml` | 独立生产配置；生产地址、账号由部署环境提供，不回退到DEV |

**不再需要同步脚本、外部properties文件，也不需要同时选择 `dev,local`。**
公共 `application.yml` 不强制默认环境，避免自动化测试误连共享数据库。
生产环境尚未提供实际参数；`prod` 是配置入口，不表示生产已部署。

## IDEA怎么启动

1. Reload Maven项目。
2. 选择对应服务的运行配置，Active profiles填 `dev`；普通Application启动项使用参数 `--spring.profiles.active=dev`。
3. 点击运行。当前这台Mac的19个已有运行项已调整；IDEA若仍显示旧参数，请重新打开项目。
4. 个人要改地址或端口时，编辑该服务 `application-local.yml` 的第二段，并改选 `local`。不要把个人覆盖提交到团队分支。

DEV数据库、Redis、Nacos的地址与开发账号直接在DEV YAML，不用每次填写。
COS云密钥、IAM加密密钥和服务间签名密钥仍使用已有IDEA环境变量，已与台式机对齐，不另建配置文件。
新同事只需由负责人一次性提供相应密钥：所有服务使用同一 `RIGOUR_CONTEXT_TRUST_KEY_V1`；IAM另需 `IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1`；所有业务服务共用同一套COS凭据（`RIGOUR_COS_SECRET_ID/KEY`，见 `docs/COS_RECORDING_SETUP.md`）。
IAM沿用既有 `~/.config/rigour/secrets/iam-dev-signing-v1.pem`（权限600）；它是签名私钥，不是新增环境配置，不要自行重新生成。
原有飞书、订货宝的IDEA凭据仍保留。DEV的飞书登录开关与台式机一致默认关闭，需要调试时使用 `RIGOUR_FEISHU_ENABLED=true`。

### 启动时执行数据库迁移

按 2026-09-16 最新确认的简化方式，各服务 DEV 数据源统一使用台式机 MySQL `root`，真实 DEV 专用密码直接写在 `application-dev.yml`。Flyway 复用同一数据源，不需要单独迁移账号、密码环境变量或额外配置文件。

已有 Flyway 迁移的 10 个服务在 `dev` 启动时自动执行本服务尚未应用的 SQL，`local` 继承同一行为。网关及暂无数据库迁移的服务不启用 Flyway；生产配置不随本次调整。

迁移失败或历史校验失败时停止启动，不自动 repair。已执行 SQL 不改写；保留 `validate-on-migrate=true`、`baseline-on-migrate=false`、`clean-disabled=true` 和 `out-of-order=false`。

个人 `local` 覆盖数据源时，迁移自动使用覆盖后的数据库。仅迁移到新版本不代表已初始化供应链租户或切换授权模式。修改配置后需要重新启动对应 IDEA 服务，已运行进程不会自动迁移。

## DEV基础设施

| 组件 | 地址 |
|---|---|
| MySQL | `192.168.12.7:13306`，各服务使用自己的 `rigour_*` 库，DEV 统一使用 `root` |
| Redis | `192.168.12.7:16379` |
| Nacos API | `192.168.12.7:18848`；gRPC端口 `19848` |
| Nacos控制台 | [打开控制台](http://192.168.12.7:18080) |
| RocketMQ NameServer | `192.168.12.7:19876` |
| 已部署门户 | [打开DEV门户](http://192.168.12.7:5100) |

Nacos Namespace名称是 `dev`，实际ID为 `3aa03547-8948-4254-bd94-47c630db128b`。
**目前应用配置以项目YAML为准，Nacos配置读取关闭，不需要再往Nacos复制一套配置。**
台式机服务使用Nacos注册发现；本机进程默认不注册，避免把个人调试实例混入同事的共享服务池。
本次未改变台式机部署脚本及其运行配置，台式机现有服务不用重启。

## 门户和业务断点调试

本机门户仍访问本机Gateway和IAM，这与“使用DEV数据库”不冲突：

1. 启动IAM（26881）、Gateway（26880）和Order（26885），都选择 `dev`。
2. 门户项目运行 `pnpm dev`，浏览器访问 [本机门户](http://localhost:5100)。
3. Gateway的订单地址指向本机Order，订单服务的供应链授权查询指向本机IAM；订单业务参数使用本机新版接口。其他业务服务地址默认指向台式机，不必在本机启动全部微服务。修改这些地址后需重启对应服务。

例如要调试ERP，在IDEA启动本机ERP，再在Gateway运行项设置：

```text
ERP_SERVICE_URL=http://localhost:26884
```

或者把该项写在Gateway的 `application-local.yml` 第二段，Gateway选择 `local`。
ERP需要调用本机Integration时，ERP的 `local` 第二段添加：

```yaml
rigour:
  integration:
    base-url: http://localhost:26882
```

其余未覆盖的服务仍使用台式机。门户/工作台已有Vite配置无需修改，本机5200的工作台按需启动。

## 仅保留必要的共享环境边界

- 本机 DEV/local 启动会使用 root 数据源更新所属共享数据库。启动前检查新增 SQL 与目标数据库。多人共用库时，不能通过修改已执行 SQL 或跳过校验消除版本冲突。
- Integration图片后台任务、订货宝定时同步和BI定时刷新默认关闭，避免个人启动后重复处理共享数据；手动业务调试仍会真实写入DEV数据库/COS。
- 自动化测试继续用测试夹具/Testcontainers，不使用共享DEV数据。
- DEV YAML中的开发账号不得转用生产；COS、签名私钥和生产凭据不提交Git。

## 本次验证范围

2026-09-15：14个服务的dev/local/prod配置加载与个人覆盖测试通过；`./mvnw verify`构建成功（1220项测试中195项跳过，本机Docker不可用，数据库容器测试未完整执行）。本机IAM、Gateway、ERP、Integration以dev启动，健康检查均返回UP；IAM Discovery/JWKS返回200。验证进程随后关闭，端口留给IDEA。未执行共享数据库迁移、未部署台式机、未做完整浏览器业务验收。
