# 本地开发环境配置

配置规则核对日期：2026-10-21。日常开发默认使用台式机 **192.168.12.7** 的基础设施，本机只启动需要调试的服务。实际连接目标以环境变量、Nacos 与 YAML 合并后的配置为准；下文历史运行记录不代表当前部署状态。

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
3. 启动前核对运行参数、Nacos 命名空间、服务注册和数据库目标。当前工程为 11 个启动应用，IDEA 历史运行项数量不能用于判断现有服务数量。
4. 个人要改地址或端口时，编辑该服务 `application-local.yml` 的第二段，并改选 `local`。不要把个人覆盖提交到团队分支。

DEV数据库、Redis、Nacos的地址与开发账号直接在DEV YAML，不用每次填写。
COS云密钥、IAM加密密钥和服务间签名密钥仍使用已有IDEA环境变量，已与台式机对齐，不另建配置文件。
新同事只需由负责人一次性提供相应密钥：所有服务使用同一 `RIGOUR_CONTEXT_TRUST_KEY_V1`；IAM另需 `IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1`；所有业务服务共用同一套COS凭据（`RIGOUR_COS_SECRET_ID/KEY`，见 `docs/COS_RECORDING_SETUP.md`）。
IAM沿用既有 `~/.config/rigour/secrets/iam-dev-signing-v1.pem`（权限600）；它是签名私钥，不是新增环境配置，不要自行重新生成。
原有飞书、订货宝的IDEA凭据仍保留。DEV的飞书登录开关与台式机一致默认关闭，需要调试时使用 `RIGOUR_FEISHU_ENABLED=true`。

### 启动时执行数据库迁移

按 2026-09-16 最新确认的简化方式，各服务 DEV 数据源统一使用台式机 MySQL `root`，真实 DEV 专用密码直接写在 `application-dev.yml`。Flyway 复用同一数据源，不需要单独迁移账号、密码环境变量或额外配置文件。

当前源码有 Flyway 迁移的 9 个领域服务在 `dev` 启动时自动执行本服务尚未应用的 SQL，`local` 继承同一行为。Gateway 和 AI 当前无迁移目录；已退出构建的协同服务历史 SQL 不计入启动服务。

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

默认 Nacos Namespace 名称是 `dev`，实际 ID 为 `3aa03547-8948-4254-bd94-47c630db128b`，可由 `NACOS_NAMESPACE` 覆盖。
当前 DEV 配置开启 Nacos 配置读取和注册，导入 `optional:nacos:rigour-common.yml` 与 `optional:nacos:${spring.application.name}.yml`。本机 DEV/local 并不天然隔离于共享服务池；个人隔离使用独立命名空间，并确认相应服务可被解析。缺少可选 dataId 不阻断启动。
团队动态参数放 Nacos，敏感凭据按现有环境变量规则管理；具体约定见 [AGENTS.md](../AGENTS.md)。本次仅核对源码和修正文档，没有重启或调整共享实例。

## 门户和业务断点调试

本机门户仍访问本机Gateway和IAM，这与“使用DEV数据库”不冲突：

1. 启动IAM（26881）、Gateway（26880）和Order（26885），都选择 `dev`。
2. 门户项目运行 `pnpm dev`，浏览器访问 [本机门户](http://localhost:5100)。
3. Gateway 默认使用 `lb://<服务名>` 路由，领域服务通过注入的 `RestClient.Builder` 按注册服务名调用。实际请求可能到达同命名空间的其他实例，不能把“页面在本机”当作“全部请求都在本机”。需要断点直连时使用相应环境变量临时覆盖，并核对生效配置。

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

其余未覆盖的服务继续按所在 Nacos 命名空间发现。门户/工作台按各自 Vite 配置访问代理；本机 5200 的工作台按需启动。

## 仅保留必要的共享环境边界

- 本机 DEV/local 启动会使用 root 数据源更新所属共享数据库。启动前检查新增 SQL 与目标数据库。多人共用库时，不能通过修改已执行 SQL 或跳过校验消除版本冲突。
- Integration图片后台任务、订货宝定时同步和BI定时刷新默认关闭，避免个人启动后重复处理共享数据；手动业务调试仍会真实写入DEV数据库/COS。
- 自动化测试继续用测试夹具/Testcontainers，不使用共享DEV数据。
- DEV YAML中的开发账号不得转用生产；COS、签名私钥和生产凭据不提交Git。

## 本次验证范围

2026-09-15：14个服务的dev/local/prod配置加载与个人覆盖测试通过；`./mvnw verify`构建成功（1220项测试中195项跳过，本机Docker不可用，数据库容器测试未完整执行）。本机IAM、Gateway、ERP、Integration以dev启动，健康检查均返回UP；IAM Discovery/JWKS返回200。验证进程随后关闭，端口留给IDEA。未执行共享数据库迁移、未部署台式机、未做完整浏览器业务验收。
