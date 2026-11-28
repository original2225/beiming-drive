-- ============================================================
-- V5：四角色 + 用户自填资料
--
-- 1. 角色从两档扩到四档：SUPER_ADMIN / ADMIN / USER / GUEST。
--    GUEST 是「不登录」的虚拟身份，不落库 —— 库里只会出现前三档。
--
-- 2. 已有的 ADMIN 一律升为 SUPER_ADMIN。理由：升级前的 ADMIN 是
--    「首个注册用户自动获得」的那一个，事实上就是站主本人。
--    不升的话新库里一个超管都没有，没人能指派管理员，系统就锁死了。
--    这条只跑一次，之后 ADMIN 就是「管理员」而不是「站主」。
--
-- 3. 用户资料：bio（简介）、qq（绑定的 QQ 号）、email_verified。
--    QQ 号允许为空（用邮箱注册的用户没有 QQ 号）。
--    绑定 QQ 号码不是「验证」—— 验证要另外走流程，这里先存下来。
-- ============================================================

-- 角色约束：只允许这三档
ALTER TABLE drv_user DROP CONSTRAINT IF EXISTS ck_drv_user_role;
ALTER TABLE drv_user ADD CONSTRAINT ck_drv_user_role
    CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'USER'));

-- 把旧的 ADMIN 提升为超管（只对升级前的存量生效）
UPDATE drv_user SET role = 'SUPER_ADMIN' WHERE role = 'ADMIN';

-- 用户资料
ALTER TABLE drv_user ADD COLUMN IF NOT EXISTS bio            VARCHAR(500);
ALTER TABLE drv_user ADD COLUMN IF NOT EXISTS qq             VARCHAR(20);
ALTER TABLE drv_user ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN drv_user.bio     IS '个人简介，用户自填，展示在个人主页';
COMMENT ON COLUMN drv_user.qq      IS '绑定的 QQ 号（纯数字），可为空';
COMMENT ON COLUMN drv_user.role    IS 'SUPER_ADMIN 超级管理员 / ADMIN 管理员 / USER 用户；GUEST 不落库';

-- QQ 号要能被查（「用 QQ 登录/找回」时要按它找人）
CREATE INDEX IF NOT EXISTS idx_drv_user_qq ON drv_user (qq) WHERE qq IS NOT NULL;
-- ============================================================
-- 安全问题：找回密码时用来证明「你是你」
--
-- 为什么不复用密码：密码忘了才来走找回，拿密码证明身份是循环论证。
-- 答案存 BCrypt 哈希，不存明文 —— 它和密码一样是凭据。
-- slot 是「第几题」，允许 1~5 题，至少 2 题（AccountService 校验）。
-- ============================================================
CREATE TABLE IF NOT EXISTS drv_security_answer (
    user_id     BIGINT      NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    slot        INT         NOT NULL,
    question    VARCHAR(100) NOT NULL,
    answer_hash VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, slot)
);
