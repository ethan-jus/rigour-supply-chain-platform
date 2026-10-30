# 全国行政区划目录

Foundation 是目录的唯一维护服务，IAM 提供带权限的后台读取接口。行政区划是全局公共参考资料；部门客户地域权限是各租户独立的业务授权，两者不共表。

## 来源和范围

- 来源：[民政部国家地名信息库行政区划版本发布](https://dmfw.mca.gov.cn/XzqhVersionPublish.html)，页面明确“数据截止日期为2025年12月31日”。获取日期：2026-10-29。
- 原始公开响应：[省地县目录接口](https://dmfw.mca.gov.cn/9095/xzqh/getList?code=&maxLevel=3)，原文保存在 `mca-20251231.raw.json`。共 34 个省级、333 个地级、2,847 个县级节点。
- 2025 接口的台湾省编码为“资料暂缺”，省级 `710000` 单独依据[民政部2024年行政区划代码表](https://www.mca.gov.cn/mzsj/xzqh/2025/202401xzqh.html)补齐。港澳台下级资料在该来源中缺失，本目录不编造下级编码。
- 以来源的 `level` 和父子关系为准，将末六位全零的12位代码规范为6位。直辖市、部分省直辖县直接以省为父级，东莞等不设区地级市保持叶子节点。不新增“市辖区”占位节点。
- 七大区域采用已确认的公司统计分组，存于既有 `GEO_REGION` 字典，省级节点保存对应代码，市县继承；行政等级沿用 `ADMIN_LEVEL` 字典。
- `MCA_20251231` 是来源版本。`effectiveFrom=2026-10-29` 是本系统采用日期，不代表来源更新至2026年；未凭空填写来源发布日期。

## 复现和校验

在仓库根目录执行：

```sh
python3 scripts/database/prepare-mca-admin-divisions.py \
  reference-data/admin-divisions/mca-20251231.raw.json \
  /tmp/mca-20251231.json --effective-from 2026-10-29
python3 scripts/database/import-admin-divisions.py /tmp/mca-20251231.json
python3 -m unittest discover -s scripts/database -p 'test_*admin_divisions.py'
```

转换器验证完整数量、省级覆盖、六位编码、重复编码、父子关系、等级和七大区域。输出版本文件不可覆盖已发布目录。正式导入流程见 [导入说明](../../docs/CRM_STORE_TERRITORIES_20261028.md)。

| 文件 | SHA256 |
| --- | --- |
| `mca-20251231.raw.json` | `2e50f7fb652571132487508e0dd9c8fae1f141e63adaab46fec7389447180739` |
| `mca-20251231.json` | `96341aa2ac02e071e7dab1406229dbb5d8df5267582e9fa91bd49bac6156bcee` |

## 开发环境启用

2026-10-29 已导入并发布 `MCA_20251231`，保留并停用 `DEV_CRM_20261028` 样本目录。校验原8个行政节点在新目录的名称、等级、父级、大区完全一致后，在事务内仅调整12家DEV门店及其查询摘要、2个测试部门范围的版本引用，并递增IAM权限配置版本。没有调整客户地域范围或迁移旧客户业务数据。执行摘要见 [DEV启用记录](../../docs/qa/admin-divisions/dev-activation-20261029.json)。

这次DEV样本衔接不等同于通用版本升级能力。后续官方区划调整仍需审核撤销、合并、改名及存量引用映射后发布；现有导入器继续拒绝直接替换已发布版本。
