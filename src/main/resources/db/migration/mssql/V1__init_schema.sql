-- ==============================================================
-- NCAS V1 Base Schema (Microsoft SQL Server)
-- ==============================================================

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets')
CREATE TABLE ServerTargets (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL DEFAULT 80,
    protocol VARCHAR(20) NOT NULL DEFAULT 'TCP',
    interval_seconds INT NOT NULL DEFAULT 5,
    timeout_ms INT NOT NULL DEFAULT 1500,
    warning_threshold_ms INT NOT NULL DEFAULT 0,
    scope VARCHAR(20) NOT NULL DEFAULT 'MULTI',
    assigned_stations VARCHAR(255) NULL,
    group_name NVARCHAR(50) NOT NULL DEFAULT 'Default',
    tags NVARCHAR(255) NULL,
    parent_id INT NULL,
    parent_agent VARCHAR(50) NULL,
    max_check_attempts INT NOT NULL DEFAULT 3,
    retry_interval INT NOT NULL DEFAULT 2,
    expected_keyword NVARCHAR(255) NULL,
    push_token VARCHAR(64) NULL,
    enabled BIT NOT NULL DEFAULT 1,
    created_at DATETIME DEFAULT GETDATE()
);

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'IncidentLogs')
CREATE TABLE IncidentLogs (
    id INT IDENTITY(1,1) PRIMARY KEY,
    target_id INT NULL,
    target_name NVARCHAR(100),
    host VARCHAR(255),
    port INT,
    protocol VARCHAR(20),
    event_type VARCHAR(50) NOT NULL,
    event_time DATETIME DEFAULT GETDATE(),
    duration_seconds INT NULL,
    message NVARCHAR(500),
    acknowledged BIT DEFAULT 0
);

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'AlertSettings')
CREATE TABLE AlertSettings (
    setting_key VARCHAR(50) PRIMARY KEY,
    setting_value NVARCHAR(500)
);

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'CheckHistory')
CREATE TABLE CheckHistory (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    target_id INT NOT NULL,
    checked_at DATETIME DEFAULT GETDATE(),
    status VARCHAR(20) NOT NULL,
    latency_ms INT NOT NULL,
    message NVARCHAR(255)
);

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'problems')
CREATE TABLE problems (
    id INT IDENTITY(1,1) PRIMARY KEY,
    target_id INT NOT NULL,
    target_name NVARCHAR(100) NOT NULL,
    source NVARCHAR(100) NOT NULL DEFAULT 'Server',
    severity VARCHAR(20) NOT NULL DEFAULT 'HIGH',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    title NVARCHAR(255) NOT NULL,
    description NVARCHAR(MAX) NULL,
    start_time DATETIME NOT NULL DEFAULT GETDATE(),
    resolved_time DATETIME NULL,
    ack_time DATETIME NULL,
    ack_user NVARCHAR(50) NULL,
    ack_comment NVARCHAR(MAX) NULL
);
