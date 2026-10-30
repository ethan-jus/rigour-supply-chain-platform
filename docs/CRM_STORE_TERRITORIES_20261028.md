# 客户地域权限与行政区划初始化

本次接续开发不依赖地图Key。范围维护属于IAM，行政区划基础目录属于Foundation，CRM读取两者并执行门店过滤。省市区县继续用行政编码；不复用旧客户业务地区编码。

## 页面与权限

- 系统设置 → 客户地域权限：部门范围列表、按部门设置省/市/县。
- 2026-10-29：行政目录独立至系统设置 → 公共资料 → 行政区划，支持逐级展开、名称/编码搜索、七大区域/行政等级筛选及来源版本说明。独立 GET `/api/v1/management/supply/admin-divisions` 检查 `supply:admin-division:read`，仅读取 Foundation，不依赖 HR。IAM V146 注册菜单，并将数据字典归入公共资料。
- `supply:territory:read`允许查看；`supply:territory:write`允许调整部门范围。IAM V144注册菜单及按钮，不自动为普通角色授权。
- 保存时核对当前租户启用部门、已发布目录、权限和应用版本。空数组表示撤销部门范围，不能解释为全国。选择省或市包含其行政下级；去掉被上级覆盖的重复选择。
- 已结束的范围保留为DISABLED；每项增减写入现有IAM操作日志，记录版本、行政编码、操作人和原因；审计与范围修改、应用版本递增在同一事务中。
- 支持本部门及下级部门的角色，按该角色分支展开部门范围。全国权限、SELF、个人上限与其他角色分支仍独立，不把不同角色的组织与地域交叉组合。
- 不配置地域的受限部门没有门店访问权。个人ADMIN_REGION上限仍沿用读取模型，本次未增加个人上限维护页面。

接口：`GET /api/v1/management/supply/store-territories`；`PUT /api/v1/management/supply/store-territories/departments/{id}`。
PUT只接受`applicationVersion`、`catalogVersion`、`areaCodes`、`reason`；租户和操作人来自已验证登录身份。

## 行政区划来源与导入

`scripts/database/import-admin-divisions.py`默认只做校验预览。行政目录是全局参考数据，不能允许某个租户管理员直接覆盖所有租户的已发布版本。由维护人员审核来源后显式导入，前端查询发布目录。

输入为UTF-8 JSON，结构示例如下，**仅示范格式，不是全国数据**：

```json
{
  "version": "SOURCE-VERSION",
  "sourceName": "来源机构及数据名称",
  "sourceRef": "可追溯来源URL或文件标识",
  "sourcePublishedOn": "2026-01-01",
  "effectiveFrom": "2026-01-01",
  "nodes": [
    {"code":"330000","name":"浙江省","level":"PROVINCE","parentCode":null},
    {"code":"330100","name":"杭州市","level":"PREFECTURE","parentCode":"330000"},
    {"code":"330105","name":"拱墅区","level":"COUNTY","parentCode":"330100"}
  ]
}
```

来源名称、引用和日期按实际数据填写；节点是该版本的有效行政区划快照。七大区域按用户确认的省级归属生成，直辖市/省直管县允许县级直接挂省。无县级建制城市保留地级叶子节点，不造虚假区县。

预览不需要数据库连接：

```bash
python3 scripts/database/import-admin-divisions.py /absolute/path/catalog.json
```

审核来源、节点数量、编码与上级关系后，使用明确的Foundation数据源配置导入。脚本需要PyYAML/PyMySQL，可使用现有uv隔离运行，不修改项目依赖。`--expected-sha256`填写预览返回的完整哈希，`--actor`填写真实操作人标识：

```bash
uv run --with pyyaml --with pymysql python scripts/database/import-admin-divisions.py /absolute/path/catalog.json \
  --config services/rg-scdp-foundation/foundation-server/src/main/resources/application-dev.yml \
  --actor OPERATOR_ID --expected-sha256 REVIEWED_SHA256 --apply --publish
```

运行前目标库需已应用Foundation V45。去掉`--publish`只导入DRAFT，不会改变当前目录。版本不允许覆盖；首次发布使用锁及事务，新版本切换需另行完成存量门店、部门范围和个人上限映射，本工具主动拒绝替换已发布版本。任何插入或发布错误整体回滚。

2026-10-29 已核验并发布全国目录 `MCA_20251231` 至共享 DEV（34省级、333地级、2847县级）；港澳台仅省级，详见[来源与复现说明](../reference-data/admin-divisions/README.md)。全国门店及旧客户数据仍未迁移，生产环境未修改。

## 10月28日历史联调记录（最新进展见全国目录说明）

需要用户现有IDEA的Foundation（26892）、IAM（26881）、HR（26889）及Gateway（26880）；CRM门店过滤联调还需CRM（26883）。不另起一套服务。10月28日只读检查IAM最新143成功，Foundation最新44成功；CRM有重复建表导致的V28失败记录，不能直接要求重启并宣称可用。数据库菜单随V144应用；仅前端文件变化不会创建真实菜单。行政目录须先有经核实的正式来源并完成首次发布。

前端隔离验收入口为`http://localhost:5100/docs/qa/crm-store/territories.html`，显著标注测试数据，保存只修改页面内存。它不代表真实账号/服务联调已验收。

## 验证记录

- IAM模块245项测试通过，包含地域持久化7项、角色下级地域1项、完整迁移及历史数据/校验保留、分层架构检查。
- 导入工具9项通过：结构校验6项，临时MySQL8.4中执行真实V45并验证持久化、外键失败回滚、并发首次发布3项。
- 前端44项相关测试通过，类型检查/生产构建通过；浏览器实际操作了部门筛选、选择省替换市、填写原因、保存回显、330105目录检索及大区继承。页面写入仅发生在演示内存中。
- 截图位于Web仓库`docs/qa/crm-store/territory-edit.png`、`territory-catalog.png`。
- `./mvnw -fae verify`完整运行结束：仅crm-server失败，其余模块及全局架构检查成功。CRM的45项应用测试因早前V28与历史门店表冲突无法启动，与本次地域管理新增迁移V144无关。详见[迁移问题记录](CRM_STORE_MIGRATION_BLOCKER_20261028.md)。
