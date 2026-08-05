# 共享DEV配置与数据库、本机服务开发指南

## 开发模式

当前统一采用以下模式：

```text
开发者A电脑                         开发者B电脑
Portal :5100                       Portal :5100
Gateway:26880                      Gateway:26880
IAM    :26881                      IAM    :26881
按需业务服务:26882-26891             按需业务服务:26882-26891
       \                              /
        +---- 共享DEV Nacos配置中心 ----+
        +---- 共享DEV业务数据库 --------+
```

`Portal`指`rigour-supply-chain-portal` Vue前端项目，不是Spring微服务。它由`pnpm dev`在每位开发者电脑的5100端口启动。

## Profile规则

所有本机Spring服务使用：

```text
dev,local
```

- `dev`加载远端DEV Nacos Namespace和其中的数据源配置。
- `local`覆盖IAM、Gateway、Portal回调为本机localhost，并默认禁止把开发者电脑注册到共享Nacos服务发现。
- 两者同时生效，不是二选一；`local`在这里是“本机运行覆盖层”，不是本地数据库环境。
- 只使用`dev`会读取Nacos，但不会启用本机loopback HTTP边界；只使用`local`则没有DEV Nacos数据源。

不要在提交到Git的基础`application.yml`中固定`spring.profiles.active=dev,local`。基础配置同时用于单元测试、CI、迁移检查和未来部署；固定后这些场景会意外连接共享DEV。每位开发者在IDEA Spring Boot Run Configuration的`Active profiles`字段保存一次`dev,local`即可，不需要每次输入Program arguments。

`application-local.yml`默认配置：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        register-enabled: false
```

因此本机服务读取共享Nacos配置，但不会把开发者A/B的局域网地址混入共享注册中心。当前Gateway使用固定localhost端口路由本机服务。如果以后确实需要本地实例参加服务发现，必须先设计按开发者隔离的group/cluster，再显式设置`NACOS_DISCOVERY_REGISTER_ENABLED=true`。

## 所有人一致的本机地址

```text
Portal:  http://localhost:5100
Gateway: http://localhost:26880
IAM:     http://localhost:26881
```

`localhost`由每位开发者自己的浏览器解析，所以数据库中的Portal回调地址可以统一登记为：

```text
http://localhost:5100/oidc/callback
http://localhost:5100/
```

开发者A登录时访问A电脑的localhost，开发者B登录时访问B电脑的localhost，二者不会互相跳转。

## 共享Secret

所有连接同一DEV IAM数据库的本机IAM必须使用相同Secret；普通DEV开关、地址和超时已统一放在Nacos YAML：

| 环境变量 | 用途 | 要求 |
|---|---|---|
| `IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1` | AES-256-GCM加密OAuth授权上下文 | 32随机字节的Base64，所有IAM相同且重启不变 |
| `~/.config/rigour/secrets/iam-dev-signing-v1.pem` | RS256签发Access/ID Token | PKCS#8 RSA-3072 PEM，权限600；所有开发者保存同一私钥 |
| `RIGOUR_CONTEXT_TRUST_KEY_V1` | Gateway到IAM/领域服务的HMAC上下文签名 | 至少32随机字节的Base64，Gateway和所有下游服务相同 |

这些值只通过各开发者IDEA Secret、受限本地Secret文件或公司Secret工具分发；Nacos只保存非敏感配置，禁止保存这些密钥明文。AES密钥可由负责人在安全终端生成一次：

```bash
openssl rand -base64 32
```

不得为每位开发者分别生成AES/RSA/HMAC密钥，否则同一共享数据库中的授权密文、JWT和下游上下文无法互认。

## IDEA运行配置

先Reload All Maven Projects。下面是共享DEV一次性初始化全部完成后的最终配置；模板中的普通开关已统一写入Nacos。新环境在密钥、V7-V11、签名公钥和Portal客户端尚未准备好时，三个OIDC开关必须保持`false`。

IDEA Community以普通`Application`配置启动时，Spring Boot可能把输出控制台判定为非终端而不输出ANSI级别颜色。这不是业务日志配置缺失。可仅在IDEA运行配置的VM options加入`-Dspring.output.ansi.enabled=ALWAYS`；不要把该选项写进项目YAML，以免生产日志采集收到ANSI转义码。

为IAM设置：

```text
Active profiles:
dev,local

Secret values only:
IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1=<负责人分发的共享AES密钥>
RIGOUR_CONTEXT_TRUST_KEY_V1=<负责人分发的共享HMAC密钥>
```

Gateway使用相同Profile，并至少配置：

```text
Active profiles:
dev,local

Secret values only:
RIGOUR_CONTEXT_TRUST_KEY_V1=<同一共享HMAC密钥>
```

Portal使用仓库已有`.env.development`：

```text
VITE_API_TARGET=http://localhost:26880
VITE_OIDC_ISSUER=http://localhost:26881
VITE_OIDC_CLIENT_ID=rigour-portal-browser
VITE_OIDC_REDIRECT_URI=http://localhost:5100/oidc/callback
VITE_OIDC_POST_LOGOUT_REDIRECT_URI=http://localhost:5100/
```

## 共享DEV一次性初始化

以下操作只由负责人执行一次，不由每位开发者重复执行：

1. 备份`rigour_iam`并确认Flyway历史正常。
2. 应用V7-V11。
3. 在`iam_signing_key`登记唯一有效RSA-3072公钥，`private_key_ref`统一使用`home-file:.config/rigour/secrets/iam-dev-signing-v1.pem`。代码会在每位开发者自己的`user.home`下解析，并拒绝越界路径、符号链接和组/其他用户可读文件。
4. 临时把Nacos或本机local YAML的`rigour.iam.bootstrap.portal-client.enabled`设为`true`，启动一次IAM，创建`rigour-portal-browser`。
5. 成功后立即把该开关恢复为`false`。
6. 初始化平台管理员、租户、套餐订阅和租户管理员；租户管理员密码只通过前台终端输入。

`rigour.iam.bootstrap.local-signing-key.enabled`不能用于此模式。它把某位开发者本机绝对文件路径写入共享数据库，其他开发者无法读取。

## 启动顺序与验收

1. 启动本机IAM。
2. 确认`http://localhost:26881/.well-known/openid-configuration`和`/oauth2/jwks`返回200。
3. 启动本机Gateway。
4. 启动Portal：`pnpm dev`。
5. 访问`http://localhost:5100`完成登录。
6. 按需启动本机业务服务。

每位开发者都使用相同端口，不会冲突，因为端口只在各自电脑上占用。共享的是配置和数据，不是本机进程。

## 登录跳转与下游故障排查

代码已在关键边界输出中文诊断日志，并遵守“不记录密码、Bearer Token、Cookie、请求体和密钥”的约束：

- Gateway：`HTTP请求完成`记录请求 ID、路径、HTTP状态和耗时；`IAM拒绝当前会话`表示明确的401/403；`IAM会话校验服务不可用`表示Gateway无法访问IAM；`下游服务暂不可用`表示业务服务连接失败。
- IAM：`IAM密码登录成功/失败`记录登录范围、租户编码、用户名和失败原因，不记录密码。
- Portal（仅Vite开发模式）：浏览器Console按`[门户]`前缀记录OIDC回调、请求ID、应用卡片、菜单加载和路由守卫决策，不记录Token。

排查时把同一个`requestId`从Portal Console对应到Gateway/IAM日志。只有明确的401才应该清理会话并回到登录页；500、超时或连接拒绝应显示服务不可用，不应伪装成重新登录。若要临时查看Gateway成功的在线会话校验，可在本机运行配置追加日志级别`com.rigour.gateway.security=DEBUG`，复现后恢复为`INFO`。

订货宝商城卡片现在是外部直达入口，指向`https://pc.dhb168.com`；浏览器会离开Portal进入第三方管理端，当前仍可能需要在第三方登录。真正免密必须由订货宝提供OIDC/SAML/一次性登录票据等协议，不能把第三方账号密码放进Portal。

订货宝数据同步页面依赖本机`26882`的Integration服务；该服务未启动时，`/api/v1/integration/**`连接拒绝只影响同步数据，不应影响系统管理和供应链的门户授权链路。Integration使用服务账号/API密钥同步数据，不能复用员工浏览器登录密码。

Integration 的共享DEV数据库、`rigour_integration_app`运行时账号、`rigour_integration_migrator`迁移账号、Flyway V1～V3和启动验收步骤见 [`docs/INTEGRATION_DATABASE_RUNTIME.md`](./INTEGRATION_DATABASE_RUNTIME.md)。数据库密码只通过本机Secret/IDEA环境变量注入，不能写入Nacos或提交到Git。

其余 9 个领域服务的共享DEV空Schema、运行账号、迁移账号和一次性初始化脚本见 [`docs/DOMAIN_DATABASE_RUNTIME.md`](./DOMAIN_DATABASE_RUNTIME.md)。这些库当前没有业务表，服务也尚未因此自动获得JDBC/Flyway能力；不能把账号登录成功当成领域服务已接入或业务接口已验收。
