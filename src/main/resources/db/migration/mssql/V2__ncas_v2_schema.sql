-- ==============================================================
-- NCAS V2 Schema Upgrade (Microsoft SQL Server, ERRATA §E8.2)
-- ==============================================================

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'CheckResults')
CREATE TABLE CheckResults (
    agent_id VARCHAR(50) NOT NULL,
    stream_id BIGINT NOT NULL,
    seq BIGINT NOT NULL,
    target_id INT NOT NULL,
    checked_at_utc BIGINT NOT NULL,
    recv_at_utc BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    latency_us BIGINT NOT NULL,
    message NVARCHAR(512),
    perf_json NVARCHAR(MAX),
    method VARCHAR(20),
    backfilled BIT NOT NULL DEFAULT 0,
    CONSTRAINT uq_check_results_seq UNIQUE (agent_id, stream_id, seq)
);

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_check_results_target_time')
CREATE INDEX idx_check_results_target_time ON CheckResults (target_id, checked_at_utc);

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_check_results_recv_time')
CREATE INDEX idx_check_results_recv_time ON CheckResults (recv_at_utc);

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'StreamState')
CREATE TABLE StreamState (
    agent_id VARCHAR(50) NOT NULL,
    stream_id BIGINT NOT NULL,
    watermark BIGINT NOT NULL DEFAULT 0,
    updated_at_utc BIGINT NOT NULL,
    PRIMARY KEY (agent_id, stream_id)
);

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'DataGaps')
CREATE TABLE DataGaps (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    agent_id VARCHAR(50) NOT NULL,
    stream_id BIGINT NOT NULL,
    from_seq BIGINT NOT NULL,
    to_seq BIGINT NOT NULL,
    reason VARCHAR(50) NOT NULL,
    reported_at_utc BIGINT NOT NULL
);

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'Rollups')
CREATE TABLE Rollups (
    target_id INT NOT NULL,
    bucket_epoch_sec BIGINT NOT NULL,
    resolution_type VARCHAR(10) NOT NULL,
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

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'Outbox')
CREATE TABLE Outbox (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    payload_json NVARCHAR(MAX) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    dedupe_key VARCHAR(128) UNIQUE,
    attempts INT NOT NULL DEFAULT 0,
    created_at_utc BIGINT NOT NULL,
    sent_at_utc BIGINT NULL
);

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'AuditLog')
CREATE TABLE AuditLog (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    action VARCHAR(50) NOT NULL,
    actor VARCHAR(50) NOT NULL,
    target NVARCHAR(100),
    details NVARCHAR(MAX),
    created_at_utc BIGINT NOT NULL
);

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'problems') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('problems') AND name = 'detected_late')
ALTER TABLE problems ADD detected_late BIT DEFAULT 0;

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'problems') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('problems') AND name = 'suppressed_by')
ALTER TABLE problems ADD suppressed_by VARCHAR(50) NULL;

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'problems') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('problems') AND name = 'diagnosis_json')
ALTER TABLE problems ADD diagnosis_json NVARCHAR(MAX) NULL;
