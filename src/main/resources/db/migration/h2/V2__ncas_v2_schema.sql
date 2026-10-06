-- ==============================================================
-- NCAS V2 Schema Upgrade (H2 Database, ERRATA §E8.2)
-- ==============================================================

-- 1. CheckResults: Lưu trữ kết quả đo kiểm phân tán bền vững, UNIQUE(agent_id, stream_id, seq)
CREATE TABLE IF NOT EXISTS CheckResults (
    agent_id VARCHAR(50) NOT NULL,
    stream_id BIGINT NOT NULL,
    seq BIGINT NOT NULL,
    target_id INT NOT NULL,
    checked_at_utc BIGINT NOT NULL,
    recv_at_utc BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    latency_us BIGINT NOT NULL,
    message VARCHAR(512),
    perf_json NVARCHAR(MAX),
    method VARCHAR(20),
    backfilled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_check_results_seq UNIQUE (agent_id, stream_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_check_results_target_time ON CheckResults (target_id, checked_at_utc);
CREATE INDEX IF NOT EXISTS idx_check_results_recv_time ON CheckResults (recv_at_utc);

-- 2. StreamState: Theo dõi Watermark theo từng luồng của Agent
CREATE TABLE IF NOT EXISTS StreamState (
    agent_id VARCHAR(50) NOT NULL,
    stream_id BIGINT NOT NULL,
    watermark BIGINT NOT NULL DEFAULT 0,
    updated_at_utc BIGINT NOT NULL,
    PRIMARY KEY (agent_id, stream_id)
);

-- 3. DataGaps: Lưu vết các khoảng chuỗi seq bị drop hoặc sampled
CREATE TABLE IF NOT EXISTS DataGaps (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    agent_id VARCHAR(50) NOT NULL,
    stream_id BIGINT NOT NULL,
    from_seq BIGINT NOT NULL,
    to_seq BIGINT NOT NULL,
    reason VARCHAR(50) NOT NULL,
    reported_at_utc BIGINT NOT NULL
);

-- 4. Rollups: Dữ liệu tổng hợp theo bucket (1 phút, 1 giờ, 1 ngày)
CREATE TABLE IF NOT EXISTS Rollups (
    target_id INT NOT NULL,
    bucket_epoch_sec BIGINT NOT NULL,
    resolution_type VARCHAR(10) NOT NULL, -- '1m', '1h', '1d'
    total_checks INT NOT NULL,
    ok_count INT NOT NULL,
    warn_count INT NOT NULL,
    crit_count INT NOT NULL,
    unknown_count INT NOT NULL,
    min_latency_us BIGINT NOT NULL,
    max_latency_us BIGINT NOT NULL,
    sum_latency_us BIGINT NOT NULL,
    PRIMARY KEY (target_id, bucket_epoch_sec, resolution_type)
);

-- 5. Outbox: Hàng đợi thông báo bền vững chống mất mát khi crash
CREATE TABLE IF NOT EXISTS Outbox (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    payload_json NVARCHAR(MAX) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, SENT, FAILED, DEAD
    dedupe_key VARCHAR(128) UNIQUE,
    attempts INT NOT NULL DEFAULT 0,
    created_at_utc BIGINT NOT NULL,
    sent_at_utc BIGINT NULL
);

-- 6. AuditLog: Nhật ký kiểm toán bảo mật và tác vụ quản trị
CREATE TABLE IF NOT EXISTS AuditLog (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    action VARCHAR(50) NOT NULL,
    actor VARCHAR(50) NOT NULL,
    target VARCHAR(100),
    details NVARCHAR(MAX),
    created_at_utc BIGINT NOT NULL
);

-- 7. Bổ sung các cột NCAS v2 cho bảng problems
ALTER TABLE problems ADD COLUMN IF NOT EXISTS detected_late BOOLEAN DEFAULT FALSE;
ALTER TABLE problems ADD COLUMN IF NOT EXISTS suppressed_by VARCHAR(50) NULL;
ALTER TABLE problems ADD COLUMN IF NOT EXISTS diagnosis_json NVARCHAR(MAX) NULL;
