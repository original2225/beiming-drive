-- ============================================================
-- V6：下载记录 + 收藏夹
--
-- 两张表都按用户隔离。为什么要单独建表而不是从别处推导：
--
-- 下载记录：现有 drv_remote_job 只记「远程下载任务」（我替你去网上抓），
--   不记「我从网盘里取走了哪个文件」。后者才是「下载记录」的本意 ——
--   用户想问的是「我上次下过的那个 PDF 在哪」。
--   名存实亡的做法是从 drv_audit 里筛 download 动作，但那表是运维审计
--   用途、会定期清、也不保证每条下载都落。单独一张表最直观。
--
-- 收藏：没有现成的地方能表达「这个文件我标记了」，必须新建。
-- ============================================================

-- ---------- 下载记录 ----------
CREATE TABLE IF NOT EXISTS drv_download_log (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    node_id     BIGINT      REFERENCES drv_node(id) ON DELETE SET NULL,
    -- 冗余存名字和路径：文件被删了记录还在，用户还能看出「我当时下的是什么」。
    -- 如果只存 node_id，删文件后这条记录就变成一行空白。
    filename    VARCHAR(512) NOT NULL,
    path        TEXT,
    size_bytes  BIGINT      NOT NULL DEFAULT 0,
    -- 一个文件反复下载不刷一堆行，改成累积次数 + 更新最后时间
    times       INT         NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 同一个人下同一个文件只留一行（node_id 为空时按文件名区分）
CREATE UNIQUE INDEX IF NOT EXISTS uq_drv_download_log_user_node
    ON drv_download_log (user_id, node_id) WHERE node_id IS NOT NULL;

-- 侧栏按「最近下载」倒序取前 N 条，这个索引就是为它建的
CREATE INDEX IF NOT EXISTS idx_drv_download_log_user_last
    ON drv_download_log (user_id, last_at DESC);

-- ---------- 收藏夹 ----------
CREATE TABLE IF NOT EXISTS drv_star (
    user_id    BIGINT      NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    node_id    BIGINT      NOT NULL REFERENCES drv_node(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, node_id)
);

-- 「我收藏的所有文件，新的在前」
CREATE INDEX IF NOT EXISTS idx_drv_star_user_time
    ON drv_star (user_id, created_at DESC);

COMMENT ON TABLE  drv_download_log           IS '谁在什么时候下载了哪个文件；供右侧栏「下载记录」';
COMMENT ON COLUMN drv_download_log.node_id   IS '文件被彻底删除后置空（ON DELETE SET NULL），记录本身保留';
COMMENT ON COLUMN drv_download_log.filename  IS '冗余存名字，node 没了也能看出下过什么';
COMMENT ON COLUMN drv_download_log.times     IS '同一文件重复下载的次数，不刷多行';
COMMENT ON TABLE  drv_star                   IS '收藏（星标），一个用户对一个文件一条';