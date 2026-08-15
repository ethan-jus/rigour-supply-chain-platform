# Business Settings Service

公共业务设置服务，为 ERP、CRM、Order 等模块提供统一业务字典。服务独占 `rigour_settings` Schema；其他服务和 Portal 只能通过 Gateway/API 访问，禁止跨库读取。

## 字典模型

- `biz_dict`：字典定义，保存编码、名称、模块、作用域、租户归属、治理状态、乐观锁版本和整本内容修订号 `revision`。
- `biz_dict_item`：字典项，使用 `parent_id` 和 `level_no` 表示树形层级。
- 作用域与层级是两个独立维度。`scope_type` 只表示字典归属，不能替代字典项父子关系。
- 生效字典按整本选择，优先级为 `TENANT -> MODULE -> SYSTEM`，不在查询时逐项猜测或合并。
- 租户基于系统级或模块级字典定制时，通过 `base_dict_id` 记录来源，并在创建时复制完整条目树。

## HTTP API

统一前缀：`/api/v1/business-settings/dictionaries`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/` | `business-settings:dict:read` | 按模块、作用域、租户和状态查询当前身份可见字典 |
| GET | `/{dictId}/items` | `business-settings:dict:read` | 查询一本字典的完整条目，管理查询包含禁用项 |
| GET | `/effective?moduleCode={module}&code={code}` | `business-settings:dict:read` | 返回当前租户最终生效的整本字典，仅包含有效条目 |
| GET | `/resolve?moduleCode={module}&code={code}` | `business-settings:dict:read` | 返回当前租户最终生效字典并包含停用项，用于历史业务数据显示 |
| POST | `/` | `business-settings:dict:write` | 新增字典；租户身份只能新增本租户字典 |
| PUT | `/{dictId}` | `business-settings:dict:write` | 修改可变属性，使用 `version` 做乐观锁校验 |
| POST | `/{dictId}/items` | `business-settings:dict:write` | 新增字典项，层级由服务端计算 |
| PUT | `/items/{itemId}` | `business-settings:dict:write` | 修改字典项，拒绝跨字典父节点和循环关系 |

内部服务接口：`POST /internal/v1/business-settings/dictionaries/items/sync`，权限为
`business-settings:dict:sync`，只接受带租户的可信 `SERVICE` 身份。该接口按来源值大小写精确补齐
当前租户最终生效字典；不创建字典定义、不猜测未知值含义、不重新启用停用项。仅当历史项的名称与
原值完全相同时，才允许用订货宝明确名称或官方枚举名称修复占位名称。单次请求最多 500 个来源值，
一个批次无论新增或修复多少项只递增一次 `revision`。

请求和返回字段的逐字段语义位于 `business-settings-api` 的 record Javadoc；数据库迁移为每一列提供了中文 `COMMENT`。治理状态固定为 `ACTIVE/DISABLED`，作用域固定为 `SYSTEM/MODULE/TENANT`，它们属于字典基础设施协议，不再反向依赖业务字典。

## 身份与数据边界

- 平台身份可维护系统级、模块级和指定租户级字典。
- 租户身份只能查询可见公共字典和本租户字典，只能修改本租户字典。
- 服务身份可读取生效字典；带 `business-settings:dict:sync` 最小权限时还可调用内部批量补齐接口，
  但不能进入管理列表或执行人工维护接口。
- `scope_id` 由服务端计算：系统级为 `SYSTEM`，模块级为 `module_code`，租户级为 `tenant_id`；调用方不能传入或覆盖。

## 日志约定

- 新增、修改字典及字典项记录 `INFO`，包含操作类型、字典/字典项主键、编码、模块、作用域、租户、操作者和新版本。
- 列表及生效字典解析记录 `DEBUG`，包含筛选范围、命中字典和结果数量。
- 日志不记录字典项 `value`、`extra_json`、数据库密码、信任密钥或请求头，避免业务信息和凭据泄漏。
- 数据库审计字段 `created_by`、`updated_by`、`created_at`、`updated_at` 与日志共同提供追踪依据。

## 同步接入约定

- ERP、CRM、Order 使用 `business-settings-client` 公共组件，不重复实现 HTTP、可信签名、去重、精确匹配和失败降级。
- 每个领域服务只声明自己明确建模的字段白名单；公共组件不会遍历 `sourceFields`、`rawJson` 或其他任意扩展字段。
- 来源返回“值+名称”时原样保存明确名称；只返回值时仅接受已登记的官方有限枚举。未知英文或数字原值
  进入 `unmapped` 审计，不再创建“名称=原值”的伪映射；订货宝直接返回中文业务值时允许等值展示。
- Settings 暂不可用或字典未定义时，公共组件返回 `unmapped` 和修订号 `-1`，领域数据仍继续落库并以 `SUCCEEDED_WITH_WARNINGS` 返回。
- V4 迁移只预置现有 ERP、CRM、Order 白名单的模块级字典定义；具体条目由实际同步数据自动补齐。

## 本地运行

使用 `dev,local` Profile，由 Nacos 提供非敏感连接配置，本机环境变量提供密码和上下文签名密钥：

```bash
export BUSINESS_SETTINGS_DB_APP_PASSWORD='<runtime-secret>'
export BUSINESS_SETTINGS_DB_MIGRATOR_PASSWORD='<migration-secret>'
export RIGOUR_CONTEXT_TRUST_KEY_V1='<base64-key>'
./mvnw -pl services/rigour-business-settings-service/business-settings-service -am spring-boot:run \
  -Dspring-boot.run.profiles=dev,local
```

默认端口为 `26892`。Nacos 配置模板见 `docs/nacos/rigour-business-settings-service.example.yml`。模板和源码迁移存在不代表共享 DEV 已发布，发布状态必须通过 Nacos 配置与 `flyway_schema_history` 只读核验。

## 验证

```bash
./mvnw -pl services/rigour-business-settings-service/business-settings-service -am test
```

测试覆盖服务启动、作用域授权与生效优先级、租户隔离、字典树父级校验、批量补齐幂等、
停用项保护、公共客户端失败降级和持久层基础行为。真实 MySQL 的建表约束仍需在目标环境执行 Flyway 后验证。
