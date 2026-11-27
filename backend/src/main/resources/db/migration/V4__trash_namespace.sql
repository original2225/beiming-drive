-- 回收站改用独立命名空间。
--
-- 问题：uq_drv_node_path 是 UNIQUE (owner_id, path)，不区分 status。
-- 回收站里的条目还占着原路径，于是「删了文件再上传同名文件」会撞唯一约束报 500
-- （实测到了：duplicate key value violates unique constraint "uq_drv_node_path"）。
--
-- 之前的绕法是「新建时给回收站里的同名让路、自动加序号」，代价是用户明明看不到
-- 同名文件，新建出来的却叫「报告 (1).docx」—— 把内部约束漏到了界面上。
--
-- 正解是把两个命名空间分开：移入回收站时把 path 搬到 /.trash/<节点id>/…，
-- 可见空间（/ 下）就永远不和回收站冲突，恢复时按 original_path 搬回去。
-- 这也是 Nextcloud/ownCloud 一类网盘的做法。
ALTER TABLE drv_node ADD COLUMN original_path TEXT;

COMMENT ON COLUMN drv_node.original_path IS
  '移入回收站前的原路径。只在 status=TRASHED 时有值，恢复时据此搬回原位。';

-- ---- 历史数据搬迁 ----
-- 已经在回收站里的条目还占着可见路径，必须一起搬，否则：
--   1. 它们仍会和新上传的同名文件撞唯一约束（这次的 bug 依旧存在）；
--   2. 它们没有 original_path，恢复时就搬不回原位了。
-- 先记 original_path，再改 path —— 顺序不能反，path 一改就不知道原来在哪。

UPDATE drv_node
SET original_path = path
WHERE status = 'TRASHED' AND original_path IS NULL;

-- 子树：相对位置 = 自己的 path 去掉「顶层条目 path」那一段前缀。
-- 顶层条目定义和 trashList 一致：父节点不是 TRASHED。
-- 必须先搬子树再搬顶层，否则子树的 LIKE 就匹配不上原前缀了。
UPDATE drv_node child
SET path = '/.trash/' || top.id || substring(child.path FROM length(top.path) + 1)
FROM drv_node top
WHERE child.status = 'TRASHED'
  AND child.path <> top.path
  AND child.path LIKE top.path || '/%'
  AND top.status = 'TRASHED'
  AND NOT EXISTS (SELECT 1 FROM drv_node p
                  WHERE p.id = top.parent_id AND p.status = 'TRASHED');

-- 顶层条目本身
UPDATE drv_node top
SET path = '/.trash/' || top.id
WHERE top.status = 'TRASHED'
  AND NOT EXISTS (SELECT 1 FROM drv_node p
                  WHERE p.id = top.parent_id AND p.status = 'TRASHED');

-- 回收站列表按「顶层条目」查，用 (owner_id, status) 的部分索引加速
CREATE INDEX idx_drv_node_trash_top
    ON drv_node (owner_id, trashed_at DESC)
    WHERE status = 'TRASHED' AND parent_id IS NULL;