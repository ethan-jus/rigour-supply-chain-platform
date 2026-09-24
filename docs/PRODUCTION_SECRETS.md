# 现有 Linux 生产凭据迁移

适用：现有 CentOS 7、Java 21，JAR 位于 `/root/rigour_scm/`。只有 Linux 操作权限，不需要云账号、KMS、CLI、Python 升级或常驻代理。

做法：本机 AES-256-GCM 密文文件 → 新 JAR 在连接 Nacos 之前解密 → Spring 配置。`source` 只加载开关和路径，`${...}` 只取值，不解密。不能将整个 env 文件加密后继续 source。

**安全边界：** 主密钥单独存放在 `keys/master.key`，密文放在 `secrets/`，目录 700、文件 600。能保护单独泄露的密文，不能抵御 root 或应用进程失陷，也不构成多个 root 服务间的隔离。主密钥与密文同时被盗仍可解密。加密不是数据库不被攻击的保证。

## 1. 本地打包，先准备 Gateway

```bash
./mvnw -pl services/rg-scdp-gateway/gateway-server -am package -DskipTests
bash scripts/production/build-local-secrets-tool.sh
```

打包使用当前工作区内容，包括尚未提交的改动。先上传下面三个文件至服务器 `/root/rigour_scm/`：

| 本地文件 | Linux 文件名 |
|---|---|
| `services/rg-scdp-gateway/gateway-server/target/gateway-server.jar` | `gateway-server.local-secrets.jar`，保留旧 JAR |
| `target/local-secrets-tool/rigour-local-secrets-tool.jar` | `rigour-local-secrets-tool.jar` |
| `scripts/production/local-prod.env.example` | `local-prod.env.example` |

工具仅在录入和校验时短暂运行；32 MB 堆上限不代表整个进程只占 32 MB。业务服务仍是原来的一个 JVM。内存紧张时先看 `free -m`、`vmstat 1 5`，不要叠加启动两份 Gateway。

## 2. 服务器公共准备，不停业务

在服务器 root 终端执行。暂时保留旧 `/etc/rigour/nacos-prod.env` 给未迁移服务及回退使用，不要将其内容发到聊天或日志。

```bash
cd /root/rigour_scm/
umask 077
install -d -m 700 /etc/rigour/local-secrets
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar init /etc/rigour/local-secrets
```

`init` 仅执行一次。已有主密钥或非空密文目录时拒绝覆盖；失败后停止后续操作，不要删除主密钥重试。主密钥丢失后无法恢复旧密文，须在可保管的安全离线位置另行备份，不要把它和密文打成同一个日常分发包。

建立新的启动参数文件，拒绝覆盖同名文件：

```bash
test ! -e /etc/rigour/local-prod.env && install -m 600 local-prod.env.example /etc/rigour/local-prod.env
```

此文件仅含：

```bash
export RIGOUR_LOCAL_SECRETS_ENABLED=true
export RIGOUR_LOCAL_SECRETS_DIR=/etc/rigour/local-secrets
```

原启动环境依赖的其他非敏感参数也应保留，如自定义服务地址。不要直接复制整个旧环境文件。

录入三个**现有真实值**。每条命令运行后在隐藏输入的提示中输入两遍，密码不进入命令行：

```bash
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar set /etc/rigour/local-secrets common spring.cloud.nacos.username
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar set /etc/rigour/local-secrets common spring.cloud.nacos.password
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar set /etc/rigour/local-secrets common rigour.context.trust.keys-base64.v1
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar check /etc/rigour/local-secrets gateway
```

第三项为现有服务间 HMAC v1，Base64 解码后至少 32 字节。必须和其他运行中服务一致，**本次不生成新 HMAC 值**。

工具支持空格、引号、反斜杠、中文；不接受空值、NUL 或含 `${` 的值，避免被 Spring 当成其他配置引用。遇到这类现存密码需单独处理，不擅自改写。每条命令失败后先排查，再继续。不要并发修改同一份密文。

`check` 只验证权限、解密和必填项，不验证外部连接或业务功能。

## 3. 迁移 Gateway

其他服务保持运行。准备及检查成功后，安排 Gateway 重启的短暂中断窗口。

```bash
/usr/local/jdk21/bin/jps -l
```

核对准确的 Gateway PID 后执行 `kill -TERM 实际PID`，等待旧进程退出和 26880 端口释放。不要把示例文字当 PID，也不要默认 `kill -9`。尚未退出时看日志排查，不启动第二个进程。

在新 SSH 会话中启动，避免继承此前 source 的全部明文密码：

```bash
cd /root/rigour_scm/
(
  set -e
  source /etc/rigour/local-prod.env
  export RIGOUR_LOCAL_SECRETS_SERVICE=gateway
  ulimit -c 0
  nohup /usr/local/jdk21/bin/java -jar gateway-server.local-secrets.jar --spring.profiles.active=prod >> gateway.log 2>&1 < /dev/null &
  echo $! > gateway.pid
)
tail -n 100 -f gateway.log
```

保留线上原有非敏感 JVM 参数；以上对应此前没有额外 JVM 参数的启动方式。`ulimit -c 0` 关闭操作系统 core dump；不要启用 `HeapDumpOnOutOfMemoryError` 将凭据写进堆转储。新加载器把 Actuator env/configprops 的值显示设为 `never`，关闭 heapdump 端点。

验收：注册到 Nacos、启动日志成功、通过现有 IAM 完成登录并调用一个受保护业务 API。只有进程在或健康接口成功，不代表业务就绪。文件更新后须重启相应服务，暂不热更新。

回退：先正常停止新 Gateway、确认退出，再 source 旧 `/etc/rigour/nacos-prod.env`，启动旧 `gateway-server.jar`。此阶段保留现有密码、HMAC 和 Nacos 配置以便回退。

## 4. 再 IAM，再逐个业务服务

每个服务都要重新打包才能包含解密能力，旧 JAR 不会因为目录里有密文就自动解密。

| 顺序 | 服务 / JAR | 公共三项以外的凭据 |
|---|---|---|
| 1 | gateway / gateway-server.jar | 无数据库 |
| 2 | iam / iam-server.jar | 数据库；已启用飞书登录时的 ID、Secret |
| 3 | foundation / foundation-server.jar | 数据库 |
| 4 | hr / hr-server.jar | 数据库 |
| 5 | crm / crm-server.jar | 数据库 |
| 6 | erp / erp-server.jar | 数据库、商品媒体 COS |
| 7 | order / order-server.jar | 数据库、已启用附件时的 COS |
| 8 | sales / sales-server.jar | 数据库、已启用录音时的 COS、已启用功能的签名密钥 |
| 9 | integration / integration-server.jar | 数据库、COS、已启用飞书时的 ID、Secret |
| 10 | bi / bi-server.jar | 数据库 |
| 按部署情况 | ai / ai-server.jar | 当前源代码关闭 Nacos 配置加载，未发现数据库/Redis 客户端依赖，单独核对线上用途 |

这是逐个迁移的建议顺序，不是严格进程依赖。全停后 Gateway 可先起进程，但受保护业务仍需 IAM 和目标服务就绪。Integration 同步、BI 刷新需等依赖服务就绪后验收。

IAM 示例：

```bash
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar set /etc/rigour/local-secrets iam spring.datasource.username
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar set /etc/rigour/local-secrets iam spring.datasource.password
# 仅在已启用飞书登录时录入下面两项：
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar set /etc/rigour/local-secrets iam rigour.iam.feishu.app-id
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar set /etc/rigour/local-secrets iam rigour.iam.feishu.app-secret
/usr/local/jdk21/bin/java -Xms16m -Xmx32m -jar rigour-local-secrets-tool.jar check /etc/rigour/local-secrets iam
```

通过后按 Gateway 方法停旧、启动新 IAM，启动块的服务名、JAR、日志、PID 文件名都换成 IAM。验收密码登录、Token 校验和已启用的飞书登录。**保留现有 IAM 签名私钥及引用方式**，本次不生成新签名密钥。

其他服务重复“录入 → check → 正常停旧 → 启动新 JAR → 功能验收”，一次一个服务。公共值不重复录入，数据库账号密码分别进入各服务密文。ID/Secret 对需完整录入，`check` 仅强制公共三项及数据库服务账号密码，不判断是否启用了 COS、飞书等功能。

| 配置 | 加密文件中的 Spring 属性名 |
|---|---|
| 数据库 | `spring.datasource.username`、`spring.datasource.password` |
| 实际使用 Redis 的服务 | `spring.data.redis.username`、`spring.data.redis.password`，未用 ACL 时不录 username |
| IAM 飞书 | `rigour.iam.feishu.app-id`、`rigour.iam.feishu.app-secret` |
| Integration 飞书 | `rigour.integration.feishu.app-id`、`rigour.integration.feishu.app-secret` |
| ERP COS | `rigour.erp.product-media.cos.secret-id`、`rigour.erp.product-media.cos.secret-key` |
| Integration COS | `rigour.integration.product-media.cos.secret-id`、`rigour.integration.product-media.cos.secret-key` |
| Order COS | `rigour.order.fund-attachment.cos.secret-id`、`rigour.order.fund-attachment.cos.secret-key` |
| Sales COS | `sales.recording.cos.secret-id`、`sales.recording.cos.secret-key` |

COS 临时凭据还需相应前缀 `.session-token` 并处理到期更新，不能视为长期密钥。Region、Bucket、地址留原配置。当前源码未发现使用 Redis 客户端，不能假设每个服务都需要 Redis 密码。

当前项目默认数据源为 `spring.datasource.*`；若线上 Nacos 配置了动态数据源/多数据库，按实际完整属性逐项迁移，不能只录一个密码。

此加载器写入 **Spring Environment**，不修改 `System.getenv()`。订货宝 `env://` 和 IAM 私钥 `env:` 等直接读取操作系统环境的引用不在本次覆盖范围，需保留已有安全注入，不能直接删掉依赖项。它们的加密迁移需单独适配与验收；已有受限权限私钥文件继续使用。Sales 其他签名配置使用实际 `rigour.sales.temporary-checkin.*` 属性逐项录入。

## 5. 验收后清理对应 Nacos 明文

新加载器通过规范 Spring 属性优先覆盖旧配置。验证后删除 Nacos 中已迁移属性的明文，保留 URL、连接池等非敏感参数。**不用把密文放回 Nacos，也不用在 Nacos 添加解密表达式。** 如果线上存在另外一套 `spring.cloud.nacos.config.password` / `discovery.password` 等更具体账号配置，要核对并分别迁移，不能假设公共账号一定覆盖这些配置。

没有 Nacos 管理权限时，本机覆盖不能清除远端明文和历史版本，仍需维护者处理。历史文件清理不能代替凭据轮换：所有使用方迁移后，在具备对应权限的情况下分批轮换数据库、COS、飞书凭据，再更新密文并验收。HMAC 和 IAM 签名密钥另行协调轮换。

全部迁移且回退窗口结束后，再将新参数统一为 `/etc/rigour/nacos-prod.env`，仅保留路径、开关和必要非敏感参数；先明确处理订货宝/私钥的原生环境变量例外，再移除已迁移明文和临时回退副本。不要第一步覆盖旧环境文件。

数据库安全还依赖网络限制和最小账号授权。可以先在 Linux 只读检查 `ss -lnt`、现有防火墙规则和数据库授权，弄清实际连接来源后限制公网访问；不要清空防火墙规则或误封 SSH/内部服务。CentOS 7 的停止维护风险无法由加密消除，本次不以系统升级为执行前提。

实现参考：[JDK 21 Cipher / GCM](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)。密文损坏、权限不合格或缺少主密钥时拒绝启动，不自动退回 Nacos 明文。
