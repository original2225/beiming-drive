-- V9 注册白名单 + 游客可浏览的公开目录标记
--
-- 花蝉 2026-11-28 定的规则：
--   「只能有我们北冥管理官方有注册网盘的权限，其他人只有使用权」
--   「注册绑定 qq 号或者 qq邮箱即可成为用户，不登录则是游客」
--
-- 「北冥管理官方」具体是哪几个人，我不能猜 —— 猜 QQ 号段或邮箱域名都会错。
-- 所以做成**白名单表**，由超管在用户管理页里自己填：
--   加一条 = 允许这个人注册；不加 = 注册直接被拒（403，不是 500）。
--
-- 为什么不复用 drv_user：
-- 白名单是「还没注册的人」，库里没有他的 user 行；
-- 等注册完再删掉白名单那条也不合适（删了就不能再改密码找回）。
-- 分开一张表，两者生命周期不同。

CREATE TABLE IF NOT EXISTS drv_register_whitelist (
    id          bigserial PRIMARY KEY,
    -- QQ 号（纯数字）或 QQ 邮箱，二选一填。都存小写以便比较
    pattern     varchar(190) NOT NULL,
    -- 'qq' = 按 QQ 号匹配（注册邮箱的前缀）；'email' = 按完整邮箱匹配
    kind        varchar(16)  NOT NULL DEFAULT 'email',
    note        varchar(190),
    created_by  bigint REFERENCES drv_user(id) ON DELETE SET NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_whitelist_kind CHECK (kind IN ('qq', 'email'))
);

-- 同一个匹配值只能有一条，否则超管重复点两次就多出一条
CREATE UNIQUE INDEX IF NOT EXISTS uq_register_whitelist
    ON drv_register_whitelist (lower(pattern), kind);

-- ---------- 存量账号回填 ----------
-- 已经在库里的两个账号（超管和花蝉的 QQ 邮箱）必须能继续用，
-- 不把它们放进白名单的话，将来他们改密码走找回流程时会被挡在外面。
-- 用 qq 邮箱的直接加 qq 规则，其他邮箱加 email 规则。
INSERT INTO drv_register_whitelist (pattern, kind, note, created_by)
SELECT lower(split_part(u.email, '@', 1)), 'qq',
       '存量账号自动加入：' || u.email, u.id
  FROM drv_user u
 WHERE lower(u.email) LIKE '%@qq.com'
   AND split_part(u.email, '@', 1) ~ '^[0-9]{5,12}$'
   AND NOT EXISTS (SELECT 1 FROM drv_register_whitelist w
                    WHERE lower(w.pattern) = lower(split_part(u.email, '@', 1))
                      AND w.kind = 'qq');

INSERT INTO drv_register_whitelist (pattern, kind, note, created_by)
SELECT lower(u.email), 'email', '存量账号自动加入：' || u.email, u.id
  FROM drv_user u
 WHERE lower(u.email) NOT LIKE '%@qq.com'
   AND NOT EXISTS (SELECT 1 FROM drv_register_whitelist w
                    WHERE lower(w.pattern) = lower(u.email)
                      AND w.kind = 'email');

-- ---------- 公开标记 ----------
-- 游客（不登录）只能下载**公开文件**。什么叫公开需要一个显式开关，
-- 不能靠「没设 ACL」来推断 —— 那正是前面踩过的「没规则=继承」的坑。
-- is_public 只对节点自己生效，不继承：把一个文件标成公开，
-- 不等于它下面的目录也公开（文件本来也没有下级）。
ALTER TABLE drv_node ADD COLUMN IF NOT EXISTS is_public boolean NOT NULL DEFAULT false;

-- 游客下载走 /share 那套口令，不走 ACL；这里只给管理员的公开列打个索引
CREATE INDEX IF NOT EXISTS idx_drv_node_public
    ON drv_node (id) WHERE is_public AND status = 'READY';
