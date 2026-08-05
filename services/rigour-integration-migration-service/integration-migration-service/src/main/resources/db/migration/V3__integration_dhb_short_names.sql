-- Integration Schema V3：统一订货宝运行时连接表短名称。
-- V1/V2 已执行的迁移文件不改写；本迁移只迁移当前表名。

RENAME TABLE integration_dinghuobao_connector TO integration_dhb_connector;
