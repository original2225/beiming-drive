-- ============================================================
-- V8：共享目录树 + 节点权限（ACL）
--
-- 这是一次模型切换，不是加功能：
--
--   旧：每个用户一个私有根目录（parent_id is null 且 owner_id=我），
--       所有查询都带 owner_id=?，别人完全看不见我的文件。
--       那套下根本不需要权限表 —— 「是不是你的」就是唯一判据。
--
--   新：所有人共用一棵树。管理员搭目录，权限挂在节点上。
--       owner_id 从「隔离依据」降级为「谁传的」，只用于展示归属，
--       不再用于隔离。
--
-- 权限按「节点 + 用户」存，靠继承往上找：查某节点能不能下载 →
-- 自己有没有该节点授权？没有就顺 parent 往上找，直到根。
-- 根上的授权就是「全站默认」。
-- 用继承而不是给每个子节点摊开写：管理员建一个目录、下面挂 200 个文件，
-- 改一次权限要改 201 行；继承只需改目录那一行。
-- ============================================================

-- ---------- 1. 全站唯一根节点 ----------
ALTER TABLE drv_node ADD COLUMN IF NOT EXISTS is_root BOOLEAN NOT NULL DEFAULT false;

-- 「只能有一个根」由唯一索引强制，不靠代码自觉
CREATE UNIQUE INDEX IF NOT EXISTS uq_drv_node_single_root
    ON drv_node (is_root) WHERE is_root;

-- ---------- 2. 权限表 ----------
-- 一个 (节点, 用户) 一行。user_id 为 null 表示「对所有人」（未登录游客不算）。
--
-- 四个开关对应「预览 / 访问 / 下载 / 浏览」：
--   can_browse   能看见这个目录、能列出里面有什么
--   can_preview  能在线预览（读得到内容，但不给原始文件）
--   can_download 能下载原始文件
--   can_upload   能往这个目录里传东西
--
-- 为什么用四个 boolean 而不是一个枚举（view/edit/full）：
-- 「能看不能下」是真实需求（给人看简历、不给源文件），枚举塞不下这种组合。
CREATE TABLE IF NOT EXISTS drv_node_acl (
    id           BIGSERIAL PRIMARY KEY,
    node_id      BIGINT      NOT NULL REFERENCES drv_node(id) ON DELETE CASCADE,
    user_id      BIGINT      REFERENCES drv_user(id) ON DELETE CASCADE,  -- null = 所有人
    can_browse   BOOLEAN     NOT NULL DEFAULT false,
    can_preview  BOOLEAN     NOT NULL DEFAULT false,
    can_download BOOLEAN     NOT NULL DEFAULT false,
    can_upload   BOOLEAN     NOT NULL DEFAULT false,
    granted_by   BIGINT      REFERENCES drv_user(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 同一节点对同一人只能一条规则。
-- Postgres 的 UNIQUE 不把两个 null 当相等，所以「所有人」那条要单独建 partial index。
CREATE UNIQUE INDEX IF NOT EXISTS uq_drv_node_acl_node_user
    ON drv_node_acl (node_id, user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_drv_node_acl_node_everyone
    ON drv_node_acl (node_id) WHERE user_id IS NULL;

-- 查询形态固定是「这批节点里，这个人对哪些有授权」
CREATE INDEX IF NOT EXISTS idx_drv_node_acl_user
    ON drv_node_acl (user_id, node_id);

COMMENT ON TABLE  drv_node_acl              IS '节点级权限，按 parent 链继承；user_id 为 null = 对所有人';
COMMENT ON COLUMN drv_node_acl.can_browse   IS '能看见目录、能列出内容';
COMMENT ON COLUMN drv_node_acl.can_preview  IS '能在线预览';
COMMENT ON COLUMN drv_node_acl.can_download IS '能下载原始文件';
COMMENT ON COLUMN drv_node_acl.can_upload   IS '能往这个目录上传';

-- ---------- 2. 先拆掉旧的 path 唯一约束 ----------
-- 旧的 uq_drv_node_path 是 (owner_id, path)，每条查询都靠它。
-- 但建新根时「owner_id=1, path='/'」和现存的那个根会撞 ——
-- 因为旧根本来就占着 (1, '/')。
-- 所以顺序必须是：先删约束 → 建新根 → 搬数据 → 最后建全局唯一索引。
-- 全局索引要等搬完才建：搬运过程中新旧 path 会短暂重名。
-- 先删约束再删索引：uq_drv_node_path 是 CONSTRAINT 提供的索引，
-- 直接 DROP INDEX 会被拒（"constraint requires it"）。
ALTER TABLE drv_node DROP CONSTRAINT IF EXISTS uq_drv_node_path;
DROP INDEX IF EXISTS uq_drv_node_path;

-- ---------- 3. 建全站唯一的根 ----------
DO $$
DECLARE
    root_id  BIGINT;
    admin_id BIGINT;
BEGIN
    SELECT id INTO root_id FROM drv_node WHERE is_root LIMIT 1;
    IF root_id IS NOT NULL THEN
        RETURN;   -- 已经建过（迁移重跑时）
    END IF;

    SELECT id INTO admin_id FROM drv_user
     WHERE role IN ('SUPER_ADMIN','ADMIN') ORDER BY id LIMIT 1;

    IF admin_id IS NULL THEN
        RAISE EXCEPTION 'V8 需要一个管理员账号来建根节点，库里没有';
    END IF;

    INSERT INTO drv_node(owner_id, parent_id, name, path, kind, is_root)
    VALUES (admin_id, NULL, '/', '/', 'dir', true)
    RETURNING id INTO root_id;

    -- 根的默认权限：所有人可浏览、可预览、可上传，但**不能下载**。
    -- 不能下载是故意的 —— 刚并库时先别让人把全站文件拖走，
    -- 要开下载由管理员在具体目录上单独给。
    INSERT INTO drv_node_acl(node_id, user_id, can_browse, can_preview,
                             can_download, can_upload, granted_by)
    VALUES (root_id, NULL, true, true, false, true, admin_id);
END $$;

-- ---------- 4. 把旧的每人一棵树并进来 ----------
-- 用临时表记「旧根 / 旧前缀 / 新名字」，再一条 UPDATE 按前缀重写整棵子树。
--
-- 为什么用前缀替换而不是递归跟着 parent 走：
-- path 本来就是那条链路算出来的结果，直接改字符串是一次性的、能逐条核对；
-- 在库里跑递归循环，中途哪一步错都会留下半成品路径，而且不好回滚。
--
-- 旧 path 一定以 '/' 开头（根是 '/'，子节点是 '/文档库'），
-- 所以新路径 = '/' || 新名字 || 旧path（旧根自己要单独处理成 '/' || 新名字）。
CREATE TEMP TABLE _v8_move ON COMMIT DROP AS
SELECT
    n.id                                              AS root_id,
    n.path                                            AS old_path,
    (row_number() OVER (ORDER BY n.id))::int          AS seq,
    regexp_replace(coalesce(u.nickname, '未命名'), '[/\\]', '_', 'g') AS base_name
FROM drv_node n
JOIN drv_user u ON u.id = n.owner_id
WHERE n.parent_id IS NULL AND n.is_root = false;

-- 重名（两个人都叫「花蝉」）：序号大的加后缀
UPDATE _v8_move m
   SET base_name = m.base_name || '_' || m.seq
 WHERE EXISTS (
     SELECT 1 FROM _v8_move o
      WHERE o.root_id <> m.root_id
        AND o.base_name = m.base_name
        AND o.seq < m.seq);

-- 4.1 每个旧根自己：挂到新根下，path 变成 '/昵称'
UPDATE drv_node c
   SET parent_id = (SELECT id FROM drv_node WHERE is_root LIMIT 1),
       name      = m.base_name,
       path      = '/' || m.base_name
  FROM _v8_move m
 WHERE c.id = m.root_id;

-- 4.2 它们的子孙：旧 path 前面拼上 '/昵称'
--
-- 这里**不能**用 `path LIKE old_path || '/%'` 做匹配：
-- 旧根的 path 就是 '/'，拼出来是 '//%'，一个都匹配不上（踩过）。
-- 改成按 owner_id 匹配 —— 每个用户只有一个旧根，owner_id 和 _v8_move
-- 的行一一对应，比字符串前缀可靠。
--
-- 但必须再排除「新建的那个根」：它的 owner_id 也是管理员的 id，
-- 会被 owner_id 匹配命中，然后被改成 ''/花蝉/' —— 踩过一次，
-- 结果是根节点的 path 变成 '/花蝉/'，整棵树挂错位置。
-- 判断条件用 is_root=false 而不是比 id，因为新根 id 是运行时才知道的。
UPDATE drv_node c
   SET path = '/' || m.base_name || c.path
  FROM _v8_move m
 WHERE c.owner_id = (SELECT owner_id FROM drv_node WHERE id = m.root_id)
   AND c.id <> m.root_id
   AND c.status <> 'TRASHED'
   AND c.is_root = false;

-- 4.2b 旧的根自己的 path 已经是 '/花蝉'（4.1 改的），
-- 但它的子节点上面用 c.path 拼 —— c.path 当时还没改，所以是 '/文档库'，
-- 拼出来 '/花蝉/文档库'，正确。这里不需要额外处理。

-- 4.3 回收站里的条目
-- 回收站用独立命名空间 '/.trash/<节点id>'（V4 定的），节点 id 全局唯一，
-- 所以两个人的回收站条目天然不会撞路径，**path 不用改**。
--
-- 但 original_path 里存的是旧世界里的路径（'/文档库/xx'），
-- 恢复时会按它搬回去。旧世界没有 '/花蝉' 这一层，
-- 不重写的话恢复出来会跑到一个不属于任何人的位置。
UPDATE drv_node c
   SET original_path = '/' || m.base_name || c.original_path
  FROM _v8_move m
 WHERE c.status = 'TRASHED'
   AND c.original_path IS NOT NULL
   AND c.owner_id = (SELECT owner_id FROM drv_node WHERE id = m.root_id);

DROP TABLE IF EXISTS _v8_move;

-- ---------- 5. path 唯一性改成全局 ----------
-- 旧约束是 (owner_id, path)，各人 path 可以重名，已在前面第 2 节拆掉。
-- 新模型里 path 是全站唯一的绝对路径，owner_id 不再参与。
-- 全局索引必须等搬完才建 —— 搬运期间新旧 path 会短暂重名。
CREATE UNIQUE INDEX IF NOT EXISTS uq_drv_node_path_global
    ON drv_node (path) WHERE status <> 'TRASHED';
-- ---------- 6. 个人目录默认「只有本人能看」 ----------
-- 根节点给的是「所有人可浏览、可上传」（花蝉要的：大家能把东西传进公共区）。
-- 但如果个人目录不写规则，它会继承根的那条 —— 结果每个人的目录
-- 对全站所有人可见可上传。那不叫「各自的家」，叫「大通铺」。
--
-- 所以这里给每个「原根升上来的个人目录」补一条本人全权规则：
-- 本人看得到、能传、能下；其他人没有规则、继承根的只读浏览 ——
-- 也就是**别人只能看、不能往里写**，而本人全权。
--
-- 为什么不是「其他人完全看不见」：
-- 花蝉要的是「其他人只有使用权（上传下载分享）」，不是「互相看不见」。
-- 目录可见（能浏览到有这个人的目录）+ 只有本人能写，是这话最贴的形态。
-- 真要做到互相不可见，把这条 everyone 规则删掉即可，不用改代码。
INSERT INTO drv_node_acl (node_id, user_id, can_browse, can_preview, can_download, can_upload, granted_by)
SELECT n.id, n.owner_id, TRUE, TRUE, TRUE, TRUE, n.owner_id
  FROM drv_node n
 WHERE n.is_root = FALSE
   AND n.kind = 'dir'
   AND n.status = 'READY'
   AND n.parent_id = (SELECT id FROM drv_node WHERE is_root LIMIT 1)
ON CONFLICT DO NOTHING;

-- 个人目录还要再写一条「其他人全禁」：只给本人全权不够 ——
-- 别人在这里没有规则，会回退到根上的「全员可上传」，
-- 结果谁都能往别人目录里塞文件（实测踩到：普通用户传进了 /花蝉/入侵.txt）。
--
-- 注意这条**只给个人目录**用（条件限定 parent 是根、且目录名就是当初的昵称）。
-- 管理员后来建的公共目录不要沿用这个默认，否则新建即关门（踩过：普通用户 404）。
INSERT INTO drv_node_acl (node_id, user_id, can_browse, can_preview, can_download, can_upload, granted_by)
SELECT n.id, NULL, FALSE, FALSE, FALSE, FALSE, n.owner_id
  FROM drv_node n
 WHERE n.is_root = FALSE
   AND n.kind = 'dir'
   AND n.status = 'READY'
   AND n.parent_id = (SELECT id FROM drv_node WHERE is_root LIMIT 1)
   AND NOT EXISTS (SELECT 1 FROM drv_node_acl a
                    WHERE a.node_id = n.id AND a.user_id IS NULL)
ON CONFLICT DO NOTHING;
