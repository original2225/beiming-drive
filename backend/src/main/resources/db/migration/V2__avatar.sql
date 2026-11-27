-- 用户头像。实体走 BlobStore（与文件正文同一套存储，备份策略一致），
-- 数据库只记两样：
--   avatar_sha  内容 sha256，读图时用它去 BlobStore 取实体
--   avatar_key  形如 avatar/3-1699999999.png，前端据此拼 URL
-- key 里带时间戳而不是固定文件名，是为了换头像后 URL 变化 ——
-- 否则浏览器会拿缓存继续显示旧图，用户以为没换成功。
ALTER TABLE drv_user ADD COLUMN avatar_sha CHAR(64);
ALTER TABLE drv_user ADD COLUMN avatar_key VARCHAR(190);
