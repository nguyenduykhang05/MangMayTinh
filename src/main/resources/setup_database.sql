-- ==============================================================
-- DATABASE INITIALIZATION SCRIPT FOR NETWORK CONNECTION ALERT SYSTEM
-- Hệ quản trị CSDL: Microsoft SQL Server (SQLEXPRESS)
-- ==============================================================

-- 1. Tạo Database nếu chưa tồn tại
IF NOT EXISTS (SELECT * FROM sys.databases WHERE name = 'NetworkAlertDB')
BEGIN
    CREATE DATABASE NetworkAlertDB;
END
GO

USE NetworkAlertDB;
GO

-- 2. Bảng Danh sách Máy chủ / Dịch vụ giám sát (ServerTargets)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets')
BEGIN
    CREATE TABLE ServerTargets (
        id INT IDENTITY(1,1) PRIMARY KEY,
        name NVARCHAR(100) NOT NULL,
        host VARCHAR(255) NOT NULL,
        port INT NOT NULL DEFAULT 80,
        protocol VARCHAR(20) NOT NULL DEFAULT 'TCP', -- ICMP, TCP, UDP, HTTP, HTTPS, DNS
        interval_seconds INT NOT NULL DEFAULT 5,
        timeout_ms INT NOT NULL DEFAULT 1500,
        warning_threshold_ms INT NOT NULL DEFAULT 0, -- 0 = dùng ngưỡng toàn cục
        scope VARCHAR(20) NOT NULL DEFAULT 'MULTI', -- SERVER, AGENT, MULTI
        assigned_stations VARCHAR(255) NULL,
        group_name NVARCHAR(50) NOT NULL DEFAULT 'Default',
        tags NVARCHAR(255) NULL,
        parent_id INT NULL,
        parent_agent VARCHAR(50) NULL,
        max_check_attempts INT NOT NULL DEFAULT 3,
        retry_interval INT NOT NULL DEFAULT 2,
        enabled BIT NOT NULL DEFAULT 1,
        created_at DATETIME DEFAULT GETDATE()
    );
END
GO

-- Nâng cấp bảng nếu đã tồn tại nhưng thiếu các cột mới
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'parent_id')
BEGIN
    ALTER TABLE ServerTargets ADD parent_id INT NULL;
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'parent_agent')
BEGIN
    ALTER TABLE ServerTargets ADD parent_agent VARCHAR(50) NULL;
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'max_check_attempts')
BEGIN
    ALTER TABLE ServerTargets ADD max_check_attempts INT NOT NULL DEFAULT 3;
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'retry_interval')
BEGIN
    ALTER TABLE ServerTargets ADD retry_interval INT NOT NULL DEFAULT 2;
END
GO

-- Nâng cấp bảng nếu đã tồn tại nhưng thiếu các cột mới
IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'warning_threshold_ms')
BEGIN
    ALTER TABLE ServerTargets ADD warning_threshold_ms INT NOT NULL DEFAULT 0;
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'scope')
BEGIN
    ALTER TABLE ServerTargets ADD scope VARCHAR(20) NOT NULL DEFAULT 'MULTI';
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'assigned_stations')
BEGIN
    ALTER TABLE ServerTargets ADD assigned_stations VARCHAR(255) NULL;
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'group_name')
BEGIN
    ALTER TABLE ServerTargets ADD group_name NVARCHAR(50) NOT NULL DEFAULT 'Default';
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'tags')
BEGIN
    ALTER TABLE ServerTargets ADD tags NVARCHAR(255) NULL;
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'expected_keyword')
BEGIN
    ALTER TABLE ServerTargets ADD expected_keyword NVARCHAR(255) NULL;
END
GO

IF EXISTS (SELECT * FROM sys.tables WHERE name = 'ServerTargets') AND NOT EXISTS (SELECT * FROM sys.columns WHERE object_id = OBJECT_ID('ServerTargets') AND name = 'push_token')
BEGIN
    ALTER TABLE ServerTargets ADD push_token VARCHAR(64) NULL;
END
GO

-- 3. Bảng Nhật ký sự cố kết nối (IncidentLogs)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'IncidentLogs')
BEGIN
    CREATE TABLE IncidentLogs (
        id INT IDENTITY(1,1) PRIMARY KEY,
        target_id INT NULL,
        target_name NVARCHAR(100),
        host VARCHAR(255),
        port INT,
        protocol VARCHAR(20),
        event_type VARCHAR(50) NOT NULL, -- 'OFFLINE', 'RECOVERED', 'HIGH_LATENCY'
        event_time DATETIME DEFAULT GETDATE(),
        duration_seconds INT NULL,
        message NVARCHAR(500),
        acknowledged BIT DEFAULT 0
    );
END
GO

-- 4. Bảng Cấu hình cảnh báo hệ thống (AlertSettings)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'AlertSettings')
BEGIN
    CREATE TABLE AlertSettings (
        setting_key VARCHAR(50) PRIMARY KEY,
        setting_value NVARCHAR(500)
    );
END
GO

-- 5. Bảng Lịch sử kiểm tra gần nhất (CheckHistory)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'CheckHistory')
BEGIN
    CREATE TABLE CheckHistory (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        target_id INT NOT NULL,
        checked_at DATETIME DEFAULT GETDATE(),
        status VARCHAR(20) NOT NULL,
        latency_ms INT NOT NULL,
        message NVARCHAR(255)
    );
END
GO

-- 5b. Bảng Sự cố theo mô hình Nagios / Zabbix (problems)
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'problems')
BEGIN
    CREATE TABLE problems (
        id INT IDENTITY(1,1) PRIMARY KEY,
        target_id INT NOT NULL,
        target_name NVARCHAR(100) NOT NULL,
        source NVARCHAR(100) NOT NULL DEFAULT 'Server',
        severity VARCHAR(20) NOT NULL DEFAULT 'HIGH', -- INFORMATION, WARNING, HIGH, DISASTER
        status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, ACKNOWLEDGED, RESOLVED
        title NVARCHAR(255) NOT NULL,
        description NVARCHAR(MAX) NULL,
        start_time DATETIME NOT NULL DEFAULT GETDATE(),
        resolved_time DATETIME NULL,
        ack_time DATETIME NULL,
        ack_user NVARCHAR(50) NULL,
        ack_comment NVARCHAR(MAX) NULL
    );
END
GO

-- 6. Dữ liệu mẫu ban đầu (Seed Data)
IF NOT EXISTS (SELECT 1 FROM ServerTargets)
BEGIN
    INSERT INTO ServerTargets (name, host, port, protocol, interval_seconds, timeout_ms, warning_threshold_ms, scope, group_name, tags, enabled) VALUES
    (N'Wi-Fi Router / Default Gateway', '192.168.100.1', 0, 'ICMP', 3, 1000, 150, 'MULTI', N'Mạng nội bộ', 'gateway,router', 1),
    (N'Google Public DNS', '8.8.8.8', 0, 'ICMP', 15, 2000, 300, 'MULTI', N'Dịch vụ DNS', 'dns,google', 1),
    (N'Cloudflare DNS', '1.1.1.1', 53, 'DNS', 15, 2000, 250, 'MULTI', N'Dịch vụ DNS', 'dns,cloudflare', 1),
    (N'Google Web HTTPS', 'google.com', 443, 'HTTPS', 20, 3500, 1000, 'MULTI', N'Web công cộng', 'web,search', 1),
    (N'Cloudflare Global Web', 'cloudflare.com', 443, 'HTTPS', 20, 3500, 1000, 'MULTI', N'Web công cộng', 'web,cdn', 1),
    (N'VnExpress Báo Mạng VN', 'vnexpress.net', 443, 'HTTPS', 20, 3500, 800, 'MULTI', N'Báo chí', 'news,vietnam', 1),
    (N'GitHub Global API', 'api.github.com', 443, 'HTTPS', 30, 4000, 1200, 'MULTI', N'Dịch vụ Cloud', 'git,api', 1),
    (N'[Giả lập] Mock TCP Server', '127.0.0.1', 9000, 'TCP', 3, 1000, 100, 'SERVER', N'Giả lập', 'mock,server', 1),
    (N'[Giả lập] Mock HTTP Server', '127.0.0.1', 9002, 'HTTP', 3, 1000, 100, 'SERVER', N'Giả lập', 'mock,server', 1),
    (N'[Giả lập] Mock UDP Echo', '127.0.0.1', 9001, 'UDP', 4, 1000, 100, 'SERVER', N'Giả lập', 'mock,server', 1);
END
GO

-- Cấu hình mặc định
IF NOT EXISTS (SELECT 1 FROM AlertSettings WHERE setting_key = 'popup_enabled')
BEGIN
    INSERT INTO AlertSettings (setting_key, setting_value) VALUES 
    ('popup_enabled', 'true'),
    ('sound_enabled', 'true'),
    ('email_enabled', 'false'),
    ('smtp_host', 'smtp.gmail.com'),
    ('smtp_port', '587'),
    ('smtp_username', ''),
    ('smtp_password', ''),
    ('smtp_from', ''),
    ('smtp_to', ''),
    ('latency_threshold_ms', '300');
END
GO
