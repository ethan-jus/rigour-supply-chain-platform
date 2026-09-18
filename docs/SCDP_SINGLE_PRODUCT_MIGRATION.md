# SCDP 单产品入口改造 — 2026-09-16

## 工程与入口

- 后端：`rigour-supply-chain-digital-platform`。
- Web：`rigour-supply-chain-digital-web`，目录与 package 名一致。
- 品牌：瑞盖供应链数字化平台；Web、IAM 登录页、favicon 和 touch icon 使用用户提供的同一 PNG 原图。
- 访问 `/` 时，已登录直接进入 `/supply-chain`，未登录显示 Web 登录表单；登录成功统一进入首页。
- 应用卡片、应用启动器、平台管理、第二套租户管理页面及对应公开管理接口已经删除。
- 用户、角色、菜单、业务参数、操作日志归供应链系统设置。HR 员工、客户主责、历史订单归属、选仓与出库分别授权等规则保留。

## 权限与迁移

- `/api/v1/me` 和 Gateway 使用的 `/api/v1/token/current` 共用当前授权计算。ACTIVE 模式只采用 SCDP 应用角色，不回退旧超级管理员通配符。
- 固定导航接口为 `/api/v1/scdp/navigation`，不接受其他 applicationCode 或租户参数。
- 首任受保护租户管理员只能先进入系统设置初始化；普通用户仍必须满足员工关联和角色授权。
- OIDC Code + PKCE、签名校验、CSRF、租户隔离与服务端鉴权继续生效。
- 新增 IAM V106：停用旧应用入口和平台账号，撤销平台会话，更新 Web 客户端公开标识。已有 `rigour-scdp-*` 客户端遇到旧名称冲突时保留其配置并停用旧客户端。
- 销售移动端接口能力归入供应链资源目录，补齐已有菜单下可配置的按钮节点，不自动新增角色授权。ACTIVE 租户需要在角色中明确授予相应移动销售能力。
- 移动端移除旧导航接口依赖，入口由 SCDP 功能权限控制。
- 历史表、引用和审计保留；306 份原有 Flyway 文件内容未改。数据库迁移不自动把 PREPARING 切为 ACTIVE。
- `platform/` 为 Maven 技术基础模块，与已移除的业务入口无关。

## 启动方式

1. VS Code 重新打开 `rigour-supply-chain-digital-web`。使用固定端口 5100；端口占用时报错，不自动切换 5101。
2. IDEA 刷新 Maven 工程并重新构建 IAM、Gateway。删除类/Mapper 后，首次升级建议 `./mvnw clean package -DskipTests`，避免旧 target 中的类被加载。
3. 启动 IAM 的 dev 配置，Flyway 执行到 V106；再启动 Gateway 和需要的业务服务（包括 HR）。DEV 数据源约定沿用现有 root 配置。
4. Web 客户端名为 `rigour-scdp-browser`；桌面部署使用 `rigour-scdp-desktop`。已同步前端 env 和部署配置转换。
5. 使用企业编码和租户账号登录。旧平台账号已退出产品；不能再用它进入供应链。新企业的租户与订阅由受控运维初始化，首任管理员使用已有 `rigour.iam.bootstrap.tenant-admin` 控制台命令建立，普通用户在 SCDP 系统设置中维护。

前后端需配套升级。租户策略版本变化会使旧 Token 失效，需要重新登录。Git origin 地址保留原地址；本次未操作远程仓库改名。

## 登录流程补充

- Web 登录表单通过同源 `/auth/scdp/session`、`/auth/scdp/login` 获取 CSRF 并建立 IAM 会话，成功后执行同源 Code + PKCE。
- Vite 与桌面 Nginx 使用相同认证端点白名单及会话 Cookie 路径；IAM issuer、客户端注册地址和 Token 签名校验保持一致。
- 删除 Web 登录中转、强制重登参数、登录前业务路由存储与恢复；错误页返回登录不会自动重登。
- Web 使用带 CSRF 的 `/auth/scdp/logout` 撤销会话，不再依赖 ID Token、退出回调参数或退出状态存储。
- IAM 原生 `/login` 仍供销售移动端系统浏览器授权使用，有现存调用方。
- 本次登录修复不涉及数据库结构，需重启 IAM 和 Web 后生效。

## 单产品入口改造时的本地验证

- 后端：47 模块 `mvn verify` 成功，1,422 项执行通过；其中按环境条件跳过的 3 项 MySQL 方言测试已在临时隔离 MySQL 单独补跑通过。
- 新增 4 项单产品入口与存量数据库升级测试通过；后续 V106 按钮目录补齐又通过 30 项 IAM 定向回归。
- IAM 的真实 Spring Security / MySQL 测试覆盖登录、Code+PKCE、换票、退出、租户边界及旧接口 404。
- Web：类型检查、lint、484 项测试、生产构建通过。
- 销售移动端：类型检查、lint、104 项测试、构建通过；未进行原生安装包或真实飞书验收。
- 部署脚本：13 项测试、Python 语法检查通过。工作树 diff 检查通过。

本次修改保留在本地 dev 工作区，没有提交、推送或部署。共享 DEV 未执行 V106，也未进行真实企业账号的跨进程浏览器验收。此时本机 IAM 未运行；需要按上述启动步骤启动后端后再登录。

## 前端登录流程修复验证（2026-09-16）

- Web：501 项测试通过，类型检查、lint、生产构建通过。
- IAM：9 项定向测试通过，包含隔离 MySQL 上的真实 CSRF、登录、会话轮换、PKCE 换码、退出与旧 Token 失效测试，未跳过。
- 代理：验证同源路径、Cookie 路径、授权查询参数转发、回登录页、端点白名单及端口冲突拒绝；Nginx 配置语法通过。
- 桌面部署辅助脚本：13 项测试通过。
- 浏览器：本机访问 5100 直接显示 Vue 登录表单并保持 Web 地址；本机 IAM 未运行时显示后端不可用提示。
- 未对共享 DEV 数据库执行写入，未使用真实企业账号完成首页业务验收。重启 IDEA IAM 和 VS Code Web 后再按真实账号联调。
