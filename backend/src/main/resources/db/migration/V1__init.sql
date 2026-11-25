-- 北冥云盘 V1 基础表
-- 命名统一前缀 drv_（drive），避免将来并入官网时与既有表冲突。

-- ========== 用户与认证 ==========
CREATE TABLE drv_user (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(190) NOT NULL UNIQUE,
    nickname        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER',   -- USER / ADMIN
    storage_quota   BIGINT       NOT NULL DEFAULT 53687091200,  -- 50GiB
    storage_used    BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / DISABLED
    wd_min          INT          NOT NULL DEFAULT 5,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE drv_session (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    token_id        VARCHAR(64) NOT NULL UNIQUE,   -- jti，登出时按此吊销
    user_agent      VARCHAR(400),
    ip              VARCHAR(64),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_drv_session_user ON drv_session(user_id);

-- ========== 文件系统 ==========
-- 用自引用树，path 存规范化全路径便于唯一约束与按前缀查询。
CREATE TABLE drv_node (
    id              BIGSERIAL PRIMARY KEY,
    owner_id        BIGINT NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    parent_id       BIGINT REFERENCES drv_node(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    path            TEXT         NOT NULL,         -- 如 /文档库/1-基本信息
    kind            VARCHAR(8)   NOT NULL,         -- dir / file
    size            BIGINT       NOT NULL DEFAULT 0,
    mime            VARCHAR(190),
    blob_key        VARCHAR(190),                  -- 存储层对象键，dir 为 null
    sha256          CHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'READY',  -- UPLOADING/READY/TRASHED
    trashed_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_drv_node_path UNIQUE (owner_id, path)
);
CREATE INDEX idx_drv_node_parent ON drv_node(owner_id, parent_id);
CREATE INDEX idx_drv_node_owner_kind ON drv_node(owner_id, kind, status);

-- ========== 上传会话（分片） ==========
CREATE TABLE drv_upload (
    id              VARCHAR(40) PRIMARY KEY,        -- 会话 id
    owner_id        BIGINT NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    parent_id       BIGINT REFERENCES drv_node(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    total_size      BIGINT NOT NULL,
    chunk_size      INT    NOT NULL,
    chunk_total     INT    NOT NULL,
    received        INT    NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- OPEN/DONE/ABORTED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_drv_upload_owner ON drv_upload(owner_id, status);

CREATE TABLE drv_upload_chunk (
    upload_id       VARCHAR(40) NOT NULL REFERENCES drv_upload(id) ON DELETE CASCADE,
    idx             INT NOT NULL,
    size            INT NOT NULL,
    sha256          CHAR(64),
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (upload_id, idx)
);

-- ========== 分享 ==========
CREATE TABLE drv_share (
    id              VARCHAR(16) PRIMARY KEY,        -- 短链 token
    owner_id        BIGINT NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    node_id         BIGINT NOT NULL REFERENCES drv_node(id) ON DELETE CASCADE,
    password_hash   VARCHAR(100),
    expires_at      TIMESTAMPTZ,
    download_count  INT NOT NULL DEFAULT 0,
    max_downloads   INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_drv_share_owner ON drv_share(owner_id);

-- ========== 远程下载任务（多线程分段下载器） ==========
CREATE TABLE drv_remote_job (
    id              VARCHAR(24) PRIMARY KEY,
    owner_id        BIGINT NOT NULL REFERENCES drv_user(id) ON DELETE CASCADE,
    url             TEXT NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    target_parent   BIGINT REFERENCES drv_node(id) ON DELETE SET NULL,
    workers         INT NOT NULL DEFAULT 8,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED/CANCELED
    total_size      BIGINT,
    done_bytes      BIGINT NOT NULL DEFAULT 0,
    chunk_size      INT,
    chunks_total    INT NOT NULL DEFAULT 0,
    chunks_done     INT NOT NULL DEFAULT 0,
    file_node_id    BIGINT REFERENCES drv_node(id) ON DELETE SET NULL,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_drv_remote_job_owner ON drv_remote_job(owner_id, status);

-- ========== 审计 ==========
CREATE TABLE drv_audit (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT,
    action          VARCHAR(64) NOT NULL,
    target          TEXT,
    result          VARCHAR(16) NOT NULL,
    detail          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_drv_audit_user_time ON drv_audit(user_id, created_at DESC);