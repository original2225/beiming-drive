-- 回收站到期清理的支撑索引。
-- TrashCleanupJob 每天按「status='TRASHED' 且 trashed_at 早于 N 天前」筛顶层条目，
-- 现有索引是 (owner_id, parent_id) 和 (owner_id, kind, status)，都走不到 trashed_at 排序上。
-- 数据量小的时候无所谓，但回收站是越攒越多的地方，等几十万条再补就慢了。
CREATE INDEX idx_drv_node_trash
    ON drv_node (status, trashed_at)
    WHERE status = 'TRASHED';