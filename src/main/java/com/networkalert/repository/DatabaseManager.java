package com.networkalert.repository;

import com.networkalert.model.DatabaseConfig;
import com.networkalert.repository.backend.DatabaseBackend;
import com.networkalert.repository.backend.DatabaseBackendType;
import com.networkalert.repository.backend.H2FileBackend;
import com.networkalert.repository.backend.SqlServerBackend;
import com.networkalert.util.LoggerUtil;
import com.networkalert.util.NamedThreadFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quản lý kết nối CSDL và chuyển tiếp thao tác tới Backend được chọn (ADR-06, Task T3.1)
 * Hỗ trợ SQL Server và tự động Fallback sang H2 file bền vững tại ./data/ncas-db.
 * Tuyệt đối không tự động chuyển đổi backend khi mất kết nối trong runtime (chống split-brain).
 */
public class DatabaseManager {
    public static final int MAX_WRITE_QUEUE_CAPACITY = 10_000;

    private static DatabaseManager instance;
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(DatabaseManager.class);

    private DatabaseConfig config;
    private DatabaseBackend backend;
    private volatile boolean connected = false;
    private String lastError = null;

    // Hàng đợi ghi có trần khi mất kết nối (ERRATA §E8.1, §E5.6)
    private final BlockingQueue<Runnable> writeQueue = new LinkedBlockingQueue<>(MAX_WRITE_QUEUE_CAPACITY);
    private final AtomicBoolean writeQueueFull = new AtomicBoolean(false);

    // Tiến trình định kỳ thử kết nối lại với exponential backoff
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("NCAS-DbReconnectWorker", true));
    private final AtomicBoolean reconnectInProgress = new AtomicBoolean(false);
    private volatile long currentBackoffMs = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    public interface ConnectionListener {
        void onConnectionStateChanged(DatabaseBackendType backendType, boolean connected, int queueDepth);
    }

    private DatabaseManager() {
        this.config = loadConfig();
        initializeBackend();
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    /**
     * Cho phép các bài kiểm thử tự động tiêm mock/in-memory backend
     */
    public synchronized void setBackendForTesting(DatabaseBackend testBackend) {
        if (this.backend != null) {
            this.backend.close();
        }
        this.backend = testBackend;
        try {
            this.backend.initializeSchema();
            this.connected = this.backend.testConnection();
            this.lastError = null;
        } catch (SQLException e) {
            this.connected = false;
            this.lastError = e.getMessage();
        }
        notifyListeners();
    }

    public synchronized void resetForTesting() {
        if (this.backend != null) {
            this.backend.close();
        }
        this.backend = null;
        this.connected = false;
        this.writeQueue.clear();
        this.writeQueueFull.set(false);
    }

    private synchronized void initializeBackend() {
        // Lựa chọn Backend DUY NHẤT MỘT LẦN lúc khởi động (ERRATA §E8.1 & §E15)
        String sysBackend = System.getProperty("ncas.db.backend");
        boolean forceMssql = "sqlserver".equalsIgnoreCase(sysBackend) || "mssql".equalsIgnoreCase(sysBackend);
        boolean forceH2 = "h2".equalsIgnoreCase(sysBackend) || "h2_file".equalsIgnoreCase(sysBackend);

        String configuredBackend = PREFS.get("db_backend", "H2"); // Mặc định H2 theo ERRATA §E8.1 & §E15
        boolean preferMssql = forceMssql || (!forceH2 && "MSSQL".equalsIgnoreCase(configuredBackend));

        if (preferMssql) {
            SqlServerBackend mssql = new SqlServerBackend(this.config);
            if (mssql.testConnection()) {
                this.backend = mssql;
                try {
                    this.backend.initializeSchema();
                    this.connected = true;
                    this.lastError = null;
                    LoggerUtil.info("[DatabaseManager] Đã kết nối và khởi tạo thành công Microsoft SQL Server.");
                    return;
                } catch (SQLException e) {
                    LoggerUtil.error("[DatabaseManager] Lỗi khởi tạo schema SQL Server: " + e.getMessage());
                }
            } else {
                LoggerUtil.warn("[DatabaseManager] Không thể kết nối SQL Server. Tự động chuyển sang CSDL tệp H2 bền vững.");
            }
        }

        // Mặc định hoặc Fallback sang H2 file bền vững (data/ncas-db)
        this.backend = new H2FileBackend();
        try {
            this.backend.initializeSchema();
            this.connected = true;
            this.lastError = null;
            LoggerUtil.info("[DatabaseManager] Đã kích hoạt CSDL tệp H2 bền vững thành công tại " + H2FileBackend.DEFAULT_DB_PATH);
        } catch (SQLException e) {
            this.connected = false;
            this.lastError = e.getMessage();
            LoggerUtil.error("[DatabaseManager] Không thể khởi tạo CSDL H2: " + e.getMessage());
        }
    }

    public void addConnectionListener(ConnectionListener listener) {
        if (listener != null) {
            connectionListeners.add(listener);
        }
    }

    private void notifyListeners() {
        DatabaseBackendType type = getBackendType();
        boolean isConn = this.connected;
        int depth = writeQueue.size();
        for (ConnectionListener listener : connectionListeners) {
            try {
                listener.onConnectionStateChanged(type, isConn, depth);
            } catch (Exception ignored) {}
        }
    }

    public DatabaseConfig loadConfig() {
        DatabaseConfig cfg = new DatabaseConfig();
        cfg.setServer(PREFS.get("db_server", "localhost"));
        cfg.setInstanceName(PREFS.get("db_instance", "SQLEXPRESS"));
        cfg.setPort(PREFS.getInt("db_port", 1433));
        cfg.setDatabaseName(PREFS.get("db_name", "NetworkAlertDB"));
        cfg.setUsername(PREFS.get("db_user", "sa"));
        cfg.setPassword(PREFS.get("db_pass", ""));
        cfg.setUseIntegratedSecurity(PREFS.getBoolean("db_integrated", false));
        return cfg;
    }

    public void saveConfig() {
        if (config != null) {
            PREFS.put("db_server", config.getServer() != null ? config.getServer() : "localhost");
            PREFS.put("db_instance", config.getInstanceName() != null ? config.getInstanceName() : "");
            PREFS.putInt("db_port", config.getPort());
            PREFS.put("db_name", config.getDatabaseName() != null ? config.getDatabaseName() : "NetworkAlertDB");
            PREFS.put("db_user", config.getUsername() != null ? config.getUsername() : "sa");
            PREFS.put("db_pass", config.getPassword() != null ? config.getPassword() : "");
            PREFS.putBoolean("db_integrated", config.isUseIntegratedSecurity());
        }
    }

    private static boolean authDllLoaded = false;

    public static synchronized void tryLoadAuthDll() {
        if (authDllLoaded) return;
        String[] possibleNames = {
                "mssql-jdbc_auth-12.8.1.x64.dll",
                "sqljdbc_auth.dll"
        };
        for (String name : possibleNames) {
            File file = new File(name);
            if (!file.exists()) {
                file = new File(System.getProperty("user.dir"), name);
            }
            if (!file.exists()) {
                file = new File("target", name);
            }
            if (file.exists()) {
                try {
                    System.load(file.getAbsolutePath());
                    LoggerUtil.info("[DatabaseManager] Đã nạp thành công native Windows Authentication DLL: " + file.getAbsolutePath());
                    authDllLoaded = true;
                    return;
                } catch (Throwable t) {
                    if (t.getMessage() != null && t.getMessage().contains("already loaded")) {
                        authDllLoaded = true;
                        return;
                    }
                }
            }
        }
    }

    public synchronized Connection getConnection() throws SQLException {
        if (backend == null) {
            initializeBackend();
        }
        try {
            Connection conn = backend.getConnection();
            if (!this.connected) {
                this.connected = true;
                this.lastError = null;
                currentBackoffMs = 1000;
                drainWriteQueue();
                notifyListeners();
            }
            return conn;
        } catch (SQLException e) {
            markConnectionLost(e.getMessage());
            throw e;
        }
    }

    public synchronized boolean testConnection() {
        if (backend == null) {
            initializeBackend();
        }
        boolean ok = backend.testConnection();
        if (ok && !this.connected) {
            this.connected = true;
            this.lastError = null;
            currentBackoffMs = 1000;
            drainWriteQueue();
            notifyListeners();
        } else if (!ok && this.connected) {
            markConnectionLost("Kiểm tra kết nối thất bại.");
        }
        return ok;
    }

    public synchronized void initializeTables() {
        if (backend == null) {
            initializeBackend();
        }
        try {
            backend.initializeSchema();
            this.connected = true;
            this.lastError = null;
        } catch (SQLException e) {
            LoggerUtil.error("[DatabaseManager] Lỗi khởi tạo bảng: " + e.getMessage());
            markConnectionLost(e.getMessage());
        }
    }

    /**
     * Ghi nhận mất kết nối trong quá trình vận hành (KHÔNG tự đổi backend)
     */
    public synchronized void markConnectionLost(String reason) {
        if (this.connected) {
            this.connected = false;
            this.lastError = reason;
            LoggerUtil.warn(String.format("⚠️ [DatabaseManager] Mất kết nối CSDL (%s): %s. Bắt đầu xếp hàng ghi (trần %d) và thử kết nối lại.",
                    getBackendType().getDisplayName(), reason, MAX_WRITE_QUEUE_CAPACITY));
            notifyListeners();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (reconnectInProgress.compareAndSet(false, true)) {
            reconnectExecutor.schedule(this::attemptReconnect, currentBackoffMs, TimeUnit.MILLISECONDS);
        }
    }

    private void attemptReconnect() {
        try {
            if (backend != null && backend.testConnection()) {
                synchronized (this) {
                    this.connected = true;
                    this.lastError = null;
                    this.currentBackoffMs = 1000;
                    LoggerUtil.info("[DatabaseManager] Kết nối CSDL đã được phục hồi! Đang xả hàng đợi ghi...");
                    drainWriteQueue();
                    notifyListeners();
                }
            } else {
                // Tiếp tục backoff
                currentBackoffMs = Math.min(currentBackoffMs * 2, MAX_BACKOFF_MS);
                reconnectExecutor.schedule(this::attemptReconnect, currentBackoffMs, TimeUnit.MILLISECONDS);
                return;
            }
        } catch (Exception e) {
            currentBackoffMs = Math.min(currentBackoffMs * 2, MAX_BACKOFF_MS);
            reconnectExecutor.schedule(this::attemptReconnect, currentBackoffMs, TimeUnit.MILLISECONDS);
            return;
        } finally {
            reconnectInProgress.set(false);
        }
    }

    /**
     * Đưa tác vụ ghi vào hàng đợi nếu CSDL đang ngoại tuyến.
     * Trả về true nếu nạp thành công, false nếu hàng đợi đã đầy (kích hoạt Backpressure).
     */
    public boolean enqueueWrite(Runnable writeOperation) {
        if (writeOperation == null) return false;
        boolean accepted = writeQueue.offer(writeOperation);
        if (accepted) {
            if (writeQueue.size() >= MAX_WRITE_QUEUE_CAPACITY) {
                writeQueueFull.set(true);
            }
            return true;
        } else {
            writeQueueFull.set(true);
            LoggerUtil.warn("[DatabaseManager] Hàng đợi ghi CSDL đã chạm trần (10.000 bản ghi)! Kích hoạt Backpressure (ngừng phát ACK).");
            return false;
        }
    }

    private synchronized void drainWriteQueue() {
        int drained = 0;
        Runnable op;
        while ((op = writeQueue.poll()) != null) {
            try {
                op.run();
                drained++;
            } catch (Exception e) {
                LoggerUtil.error("[DatabaseManager] Lỗi khi xả tác vụ ghi từ hàng đợi: " + e.getMessage());
            }
        }
        writeQueueFull.set(false);
        if (drained > 0) {
            LoggerUtil.info("[DatabaseManager] Đã xả thành công " + drained + " tác vụ ghi tồn đọng vào CSDL.");
        }
    }

    public boolean isWriteQueueFull() {
        return writeQueueFull.get() || writeQueue.size() >= MAX_WRITE_QUEUE_CAPACITY;
    }

    public int getPendingWriteCount() {
        return writeQueue.size();
    }

    public DatabaseBackendType getBackendType() {
        return backend != null ? backend.getType() : DatabaseBackendType.H2_FILE;
    }

    public String getBackendStatusText() {
        if (!connected) {
            return "Mất kết nối (" + getBackendType().getDisplayName() + ")";
        }
        return getBackendType().getDisplayName();
    }

    public DatabaseBackend getBackend() {
        return backend;
    }

    public DatabaseConfig getConfig() {
        return config;
    }

    public void setConfig(DatabaseConfig config) {
        this.config = config;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getLastError() {
        return lastError;
    }
}
