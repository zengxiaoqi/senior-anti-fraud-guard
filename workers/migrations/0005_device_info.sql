-- 老人端设备品牌/型号（Build.BRAND / Build.MODEL）
-- 随 LOCATION_UPDATE 心跳上报，供家属端「广告源头关闭指引」自动匹配品牌步骤。
ALTER TABLE users ADD COLUMN device_brand TEXT;
ALTER TABLE users ADD COLUMN device_model TEXT;
