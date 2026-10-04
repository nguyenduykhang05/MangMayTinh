-- ==============================================================
-- NCAS V1 Base Schema (H2 Database)
-- ==============================================================

-- 1. ServerTargets
CREATE TABLE IF NOT EXISTS ServerTargets (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL DEFAULT 80,
    protocol VARCHAR(20) NOT NULL DEFAULT 'TCP',
    interval_seconds INT NOT NULL DEFAULT 5,
    timeout_ms INT NOT NULL DEFAULT 1500,
    warning_threshold_ms BIGINT NOT NULL DEFAULT 0,
    scope VARCHAR(20) NOT NULL DEFAULT 'MULTI',
    assigned_stations VARCHAR(255) NULL,
    group_name VARCHAR(50) NOT NULL DEFAULT 'Default',
    tags VARCHAR(255) NULL,
    parent_id INT NULL,
    parent_agent VARCHAR(50) NULL,
    max_check_attempts INT NOT NULL DEFAULT 3,
    retry_interval INT NOT NULL DEFAULT 2,
    expected_keyword VARCHAR(255) NULL,
    push_token VARCHAR(64) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. IncidentLogs
CREATE TABLE IF NOT EXISTS IncidentLogs (
    id INT IDENTITY(1,1) PRIMARY KEY,
    target_id INT NULL,
    target_name VARCHAR(100),
    host VARCHAR(255),
    port INT,
    protocol VARCHAR(20),
    event_type VARCHAR(50) NOT NULL,
    event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    duration_seconds INT NULL,
    message VARCHAR(500),
    acknowledged BOOLEAN DEFAULT FALSE
);

-- 3. AlertSettings
CREATE TABLE IF NOT EXISTS AlertSettings (
    setting_key VARCHAR(50) PRIMARY KEY,
    setting_value VARCHAR(500)
);

-- 4. CheckHistory
CREATE TABLE IF NOT EXISTS CheckHistory (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    target_id INT NOT NULL,
    checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    latency_ms INT NOT NULL,
    message VARCHAR(255)
);

-- 5. problems
CREATE TABLE IF NOT EXISTS problems (
    id INT IDENTITY(1,1) PRIMARY KEY,
    target_id INT NOT NULL,
    target_name VARCHAR(100) NOT NULL,
    source VARCHAR(100) NOT NULL DEFAULT 'Server',
    severity VARCHAR(20) NOT NULL DEFAULT 'HIGH',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    title VARCHAR(255) NOT NULL,
    description NVARCHAR(MAX) NULL,
    start_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_time TIMESTAMP NULL,
    ack_time TIMESTAMP NULL,
    ack_user VARCHAR(50) NULL,
    ack_comment NVARCHAR(MAX) NULL
);
