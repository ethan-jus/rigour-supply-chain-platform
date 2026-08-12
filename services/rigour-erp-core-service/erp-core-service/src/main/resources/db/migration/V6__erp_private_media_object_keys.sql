-- 所有图片只保存我方 COS 私桶 object key，禁止业务表保存订货宝或永久公开 URL。
ALTER TABLE erp_brand
    RENAME COLUMN logo_url TO logo_object_key;
