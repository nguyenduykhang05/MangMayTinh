package com.networkalert.security;

import com.networkalert.util.LoggerUtil;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Nhà máy tạo lập và cấu hình ngữ cảnh bảo mật TLS (SSLContext) cho NCAS 2.0 (ERRATA §E2.1 & TASKS T1.3).
 *
 * <p>Quy tắc an ninh bắt buộc:
 * <ul>
 *   <li>Tối thiểu TLS 1.2, ưu tiên TLS 1.3 nếu JRE hỗ trợ.</li>
 *   <li>Nghiêm cấm tuyệt đối các giao thức cũ: TLS 1.0, TLS 1.1, SSLv3, SSLv2Hello.</li>
 *   <li>Định dạng Keystore chuẩn PKCS12 tại {@code data/tls/keystore.p12}.</li>
 *   <li>Hỗ trợ ghim Fingerprint SHA-256 đối chiếu qua {@link FingerprintTrustManager}.</li>
 * </ul>
 */
public final class TlsContextFactory {

    public static final String KEYSTORE_TYPE = "PKCS12";
    public static final String DEFAULT_ALIAS = "ncas-server";
    public static final List<String> ALLOWED_PROTOCOLS = Collections.unmodifiableList(
            Arrays.asList("TLSv1.3", "TLSv1.2")
    );

    private TlsContextFactory() {}

    /**
     * Cấu hình SSLServerSocket chỉ chấp nhận TLS 1.2 và TLS 1.3
     */
    public static void configureServerSocket(SSLServerSocket serverSocket) {
        if (serverSocket == null) return;
        String[] supported = serverSocket.getSupportedProtocols();
        List<String> enabled = new ArrayList<>();
        for (String p : ALLOWED_PROTOCOLS) {
            for (String s : supported) {
                if (p.equalsIgnoreCase(s)) {
                    enabled.add(p);
                    break;
                }
            }
        }
        if (enabled.isEmpty()) {
            throw new IllegalStateException("JRE hiện tại không hỗ trợ TLS 1.2 hoặc TLS 1.3!");
        }
        serverSocket.setEnabledProtocols(enabled.toArray(new String[0]));
    }

    /**
     * Cấu hình SSLSocket phía Client chỉ sử dụng TLS 1.2 và TLS 1.3
     */
    public static void configureClientSocket(SSLSocket socket) {
        if (socket == null) return;
        String[] supported = socket.getSupportedProtocols();
        List<String> enabled = new ArrayList<>();
        for (String p : ALLOWED_PROTOCOLS) {
            for (String s : supported) {
                if (p.equalsIgnoreCase(s)) {
                    enabled.add(p);
                    break;
                }
            }
        }
        if (enabled.isEmpty()) {
            throw new IllegalStateException("JRE hiện tại không hỗ trợ TLS 1.2 hoặc TLS 1.3!");
        }
        socket.setEnabledProtocols(enabled.toArray(new String[0]));
    }

    /**
     * Nạp KeyStore PKCS12 từ đường dẫn file
     */
    public static KeyStore loadKeyStore(Path keystorePath, char[] password) throws Exception {
        if (keystorePath == null || !Files.exists(keystorePath)) {
            throw new IllegalArgumentException("Không tìm thấy file Keystore tại: " + keystorePath);
        }
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        try (InputStream in = Files.newInputStream(keystorePath)) {
            ks.load(in, password != null ? password : new char[0]);
        }
        return ks;
    }

    /**
     * Khởi tạo Server SSLContext từ đối tượng KeyStore
     */
    public static SSLContext createServerSSLContext(KeyStore keyStore, char[] password) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password != null ? password : new char[0]);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());
        return sslContext;
    }

    /**
     * Khởi tạo Server SSLContext từ đường dẫn Keystore
     */
    public static SSLContext createServerSSLContext(Path keystorePath, char[] password) throws Exception {
        KeyStore ks = loadKeyStore(keystorePath, password);
        return createServerSSLContext(ks, password);
    }

    /**
     * Khởi tạo Client SSLContext kèm ghim Fingerprint SHA-256
     */
    public static SSLContext createClientSSLContext(List<String> pinnedFingerprints) throws Exception {
        FingerprintTrustManager tm = new FingerprintTrustManager(pinnedFingerprints);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { tm }, new SecureRandom());
        return sslContext;
    }

    /**
     * Trích xuất Fingerprint SHA-256 của chứng chỉ máy chủ trong Keystore
     */
    public static String getCertificateFingerprint(KeyStore keyStore, String alias) throws Exception {
        if (keyStore == null) return null;
        String chosenAlias = alias;
        if (chosenAlias == null) {
            Enumeration<String> aliases = keyStore.aliases();
            if (aliases.hasMoreElements()) {
                chosenAlias = aliases.nextElement();
            }
        }
        if (chosenAlias == null) {
            throw new IllegalArgumentException("Keystore không chứa bất kỳ alias nào!");
        }
        Certificate cert = keyStore.getCertificate(chosenAlias);
        if (cert instanceof X509Certificate) {
            return FingerprintTrustManager.calculateFingerprint((X509Certificate) cert);
        }
        throw new IllegalArgumentException("Alias '" + chosenAlias + "' không phải là chứng chỉ X509Certificate!");
    }

    /**
     * Trích xuất Fingerprint SHA-256 của chứng chỉ máy chủ từ file Keystore
     */
    public static String getCertificateFingerprint(Path keystorePath, char[] password) throws Exception {
        KeyStore ks = loadKeyStore(keystorePath, password);
        return getCertificateFingerprint(ks, null);
    }

    /**
     * Sinh tự động Keystore PKCS12 tự ký sử dụng JDK keytool (tiện ích bootstrap và kiểm thử)
     */
    public static Path generateSelfSignedKeystore(Path targetPath, char[] password, String alias, int validityDays) throws Exception {
        if (targetPath.getParent() != null && !Files.exists(targetPath.getParent())) {
            Files.createDirectories(targetPath.getParent());
        }

        String keytoolExe = System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path keytoolBin = Path.of(System.getProperty("java.home"), "bin", keytoolExe);
        String keytoolCmd = Files.exists(keytoolBin) ? keytoolBin.toAbsolutePath().toString() : "keytool";

        String chosenAlias = (alias != null && !alias.trim().isEmpty()) ? alias.trim() : DEFAULT_ALIAS;
        String passStr = password != null ? new String(password) : "changeit";

        if (Files.exists(targetPath)) {
            Files.delete(targetPath);
        }

        ProcessBuilder pb = new ProcessBuilder(
                keytoolCmd,
                "-genkeypair",
                "-alias", chosenAlias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-storetype", KEYSTORE_TYPE,
                "-keystore", targetPath.toAbsolutePath().toString(),
                "-storepass", passStr,
                "-validity", String.valueOf(validityDays > 0 ? validityDays : 365),
                "-dname", "CN=NCAS Server, O=NCAS Distributed Monitoring, C=VN",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            String err = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("keytool sinh chứng chỉ thất bại (exit " + exitCode + "): " + err);
        }
        return targetPath;
    }
}
