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

所有连接同一DEV IAM数据库的本机IAM必须使用相同Secret：

| 环境变量 | 用途 | 要求 |
|---|---|---|
| `IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1` | AES-256-GCM加密OAuth授权上下文 | 32随机字节的Base64，所有IAM相同且重启不变 |
| `~/.config/rigour/secrets/iam-dev-signing-v1.pem` | RS256签发Access/ID Token | PKCS#8 RSA-3072 PEM，权限600；所有开发者保存同一私钥 |
| `RIGOUR_CONTEXT_TRUST_KEY_V1` | Gateway到IAM/领域服务的HMAC上下文签名 | 至少32随机字节的Base64，Gateway和所有下游服务相同 |

这些值只通过各开发者IDEA环境变量或公司Secret工具分发；Nacos仅保存`${变量名}`引用，禁止保存明文。AES密钥可由负责人在安全终端生成一次：

```bash
openssl rand -base64 32
```

不得为每位开发者分别生成AES/RSA/HMAC密钥，否则同一共享数据库中的授权密文、JWT和下游上下文无法互认。

## IDEA运行配置

先Reload All Maven Projects。下面是共享DEV一次性初始化全部完成后的最终配置；密钥、V7/V8、签名公钥和Portal客户端尚未准备好时，三个OIDC开关必须保持`false`。

IDEA Community以普通`Application`配置启动时，Spring Boot可能把输出控制台判定为非终端而不输出ANSI级别颜色。这不是业务日志配置缺失。可仅在IDEA运行配置的VM options加入`-Dspring.output.ansi.enabled=ALWAYS`；不要把该选项写进项目YAML，以免生产日志采集收到ANSI转义码。

为IAM设置：

```text
Active profiles:
dev,local

Environment variables:
IAM_OIDC_SERVER_ENABLED=true
IAM_OIDC_SIGNING_ENABLED=true
IAM_OIDC_AUTHORIZATION_STORE_ENABLED=true
IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1=<负责人分发的共享AES密钥>
RIGOUR_CONTEXT_TRUST_KEY_V1=<负责人分发的共享HMAC密钥>
```

Gateway使用相同Profile，并至少配置：

```text
Active profiles:
dev,local

Environment variables:
GATEWAY_SECURITY_ENABLED=true
GATEWAY_CURRENT_TOKEN_VALIDATION_ENABLED=true
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

1. 备份`rigour_iam`并确认Flyway V1-V6历史正常。
2. 应用V7/V8。
3. 在`iam_signing_key`登记唯一有效RSA-3072公钥，`private_key_ref`统一使用`home-file:.config/rigour/secrets/iam-dev-signing-v1.pem`。代码会在每位开发者自己的`user.home`下解析，并拒绝越界路径、符号链接和组/其他用户可读文件。
4. 临时设置`IAM_PORTAL_CLIENT_BOOTSTRAP_ENABLED=true`，启动一次IAM，创建`rigour-portal-browser`。
5. 成功后立即把`IAM_PORTAL_CLIENT_BOOTSTRAP_ENABLED`恢复为`false`。
6. 初始化平台管理员、租户、套餐订阅和租户管理员。

`IAM_LOCAL_SIGNING_KEY_BOOTSTRAP_ENABLED`不能用于此模式。它把某位开发者本机绝对文件路径写入共享数据库，其他开发者无法读取。

## 启动顺序与验收

1. 启动本机IAM。
2. 确认`http://localhost:26881/.well-known/openid-configuration`和`/oauth2/jwks`返回200。
3. 启动本机Gateway。
4. 启动Portal：`pnpm dev`。
5. 访问`http://localhost:5100`完成登录。
6. 按需启动本机业务服务。

每位开发者都使用相同端口，不会冲突，因为端口只在各自电脑上占用。共享的是配置和数据，不是本机进程。
