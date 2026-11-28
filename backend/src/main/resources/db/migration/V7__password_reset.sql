-- ============================================================
-- V7：忘记密码（安全问题换票 → 凭票改密码）
--
-- 拆两张表而不是往 drv_user 上堆字段：
--   drv_password_reset  一次性重置票，10 分钟过期，用过即废
--   drv_reset_attempt   答题失败记录，用来算「15 分钟内错了几次」
--
-- 为什么失败次数要单独落表而不是内存计数：
-- 重启后计数器清零 = 攻击者可以靠重启服务重置限速（虽然够不着），
-- 更重要的是多实例部署时内存计数不共享，限速形同虚设。
-- ============================================================

CREATE TABLE IF NOT EXISTS drv_password_reset (
    id         BIGSERIAL PRIMARY KEY,
    ticket     VARCHAR(64) NOT NULL UNIQUE,
    user_id    BIGINT      NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 查票只按 ticket 查，刚才那个 UNIQUE 就是索引；这里再补一个
-- 清理过期票用的（定时任务会按 expires_at 扫）
CREATE INDEX IF NOT EXISTS idx_drv_password_reset_exp
    ON drv_password_reset (expires_at);

CREATE TABLE IF NOT EXISTS drv_reset_attempt (
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT      NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 「这个用户最近 15 分钟错了几次」是唯一的查询形态
CREATE INDEX IF NOT EXISTS idx_drv_reset_attempt_user_time
    ON drv_reset_attempt (user_id, at DESC);

COMMENT ON TABLE  drv_password_reset        IS '一次性密码重置票；用过置 used_at，10 分钟过期';
COMMENT ON COLUMN drv_password_reset.ticket IS '32 字节随机数的 hex（64 字符）';
COMMENT ON TABLE  drv_reset_attempt         IS '安全问题答错记录，用于 15 分钟 5 次的限速';