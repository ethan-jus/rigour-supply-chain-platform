# IAM与统一门户本地验收手册

## 前提与边界

- 当前无IAM、Gateway、Portal域名；每位开发者分别在自己的电脑使用`localhost:26881`、`localhost:26880`、`localhost:5100`。
- 本机开发服务也可通过Vite日志中的动态`lanUrls`被同一局域网访问；IAM的`allow-insecure-lan`只允许local调试时的RFC1918私网HTTP来源，生产必须关闭。
- 服务在各开发者本机运行，配置和数据使用共享DEV Nacos/真实DEV数据库；不启用运行时Mock。完整模式见`docs/SHARED_DEV_LOCAL_RUNTIME.md`。
- 本机`local`覆盖层默认不向共享Nacos注册服务实例，防止开发者A/B的电脑地址互相污染；共享Nacos仍正常提供配置。
- V7-V11、初始管理员、OIDC客户端和签名密钥都会改变共享DEV，必须先备份、评审并单次执行。
- 本文命令均为前台命令，不建议后台常驻。管理员密码只能由负责人在本机交互终端输入。

## 共享DEV一次性准备

1. 备份`rigour_iam`，评审V7～V11，确认Flyway历史连续且无checksum异常。
2. 每位开发者将相同的RSA-3072私钥保存到`~/.config/rigour/secrets/iam-dev-signing-v1.pem`并设置权限600；数据库只登记公开JWK与`home-file:.config/rigour/secrets/iam-dev-signing-v1.pem`引用。`IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1`仍通过本机Secret注入。
3. 为Gateway、IAM和所有下游服务注入同一份`RIGOUR_CONTEXT_TRUST_KEY_V1`（至少32随机字节的Base64）。它只用于Gateway到服务的HMAC身份上下文签名，不能与OIDC或数据库密钥复用。
4. 临时开启Portal Client bootstrap，注册回调`http://localhost:5100/oidc/callback`和退出回调`http://localhost:5100/`，成功后立即恢复关闭。
5. 交互式初始化平台管理员；在平台管理中创建租户、发布套餐版本并开通订阅；再交互式初始化该租户管理员。
6. 确认一次性bootstrap开关全部恢复`false`。`local-signing-key`只适用隔离数据库，不得用于多人共用DEV库。

## 本地前台启动

所有密钥和密码由各开发者自己的环境或Secret工具注入，不写入命令历史和Git。

```bash
SPRING_PROFILES_ACTIVE=dev,local \
./mvnw -pl services/rigour-tenant-iam-service/iam-service -am spring-boot:run
```

OIDC开关、issuer和Gateway安全开关已写入DEV Nacos/local YAML；启动命令只选择Profile和读取Secret。

```bash
SPRING_PROFILES_ACTIVE=dev,local \
./mvnw -pl services/rigour-api-gateway -am spring-boot:run
```

```bash
cd ../rigour-supply-chain-portal
pnpm dev
```

## 功能验收清单

1. 首次访问`http://localhost:5100`，Portal跳到IAM；登录成功后回到`/apps`。
   局域网访问只应使用启动日志提供的当前地址；OIDC登录回调仍必须使用IAM已精确注册的HTTPS或localhost地址。
2. ID Token签名、issuer、audience、azp、nonce和时间声明验证通过；Access/ID Token不出现在localStorage/sessionStorage。
3. 平台管理员只看到平台级卡片与菜单，可管理租户、套餐/不可变版本、应用、公开PKCE客户端、MENU/PAGE/BUTTON/API资源和审计。
4. 租户管理员看到系统管理、供应链和订货宝商城系统卡片；订货宝商城卡片只负责直达第三方管理端，订货宝数据同步仍位于供应链菜单，可管理组织、用户、角色、数据范围、租户设置和审计。
5. 角色只能授予当前有效套餐内的资源；未授权菜单/页面/按钮不可见，直接输入路径返回403，后端写操作仍拒绝。
6. 应用A登录后点击第二个OIDC公开客户端，IAM复用已有HttpOnly会话，无需再次输入密码；订货宝商城当前验证的是外部直达，真正无感登录需另行验收供应商SSO协议。
7. 验证错误密码与锁定、缺少PKCE、错误state/nonce/回调、伪造`X-Rigour-*`、错误issuer/audience/签名均被拒绝。
8. 退出、禁用用户或改变角色后，旧Access Token的下一次Gateway请求立即失败。IAM业务拒绝是401；IAM服务不可用时Gateway是503，不伪装成登录失效。
9. 绕过Gateway直连领域服务并伪造`X-Rigour-*`、篡改请求路径/查询或重放过期签名均返回401；缺少权限的已认证请求返回403。

## 新业务页面接入规范

1. 先在IAM平台管理创建或选择租户应用。
2. 为MENU/PAGE分配稳定`routeKey`，Portal同步增加编译时路由映射；数据库不保存Vue组件路径。
3. 为BUTTON/API分配稳定`permissionCode`，页面用内置权限判断做体验控制，后端用`AuthorizationContext.requirePermission`做最终授权。
4. 将资源加入新的套餐草稿版本，发布后再更新租户订阅；已发布套餐版本不原地修改。
5. 租户管理员将新资源授予角色，用真实DEV数据做前后端验收。
6. 业务Controller读取身份和做后端授权时遵循`docs/DOMAIN_AUTHORIZATION_GUIDE.md`；显示姓名、组织等可变资料不得作为签名授权事实。
