package com.networkalert.security;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * TrustManager tùy biến thực hiện ghim Fingerprint SHA-256 của chứng chỉ lá (Leaf Certificate)
 * theo đặc tả NCAS 2.0 (ERRATA §E2.1 & TASKS T1.3).
 *
 * <p>Đặc tính an ninh:
 * <ul>
 *   <li>So sánh Fingerprint dùng {@link MessageDigest#isEqual(byte[], byte[])} chống tấn công kênh bên (Timing Attack).</li>
 *   <li>Hỗ trợ danh sách nhiều Fingerprint để xoay chứng chỉ không gián đoạn dịch vụ (Zero-Downtime Certificate Rotation).</li>
 *   <li>Chuẩn hóa định dạng linh hoạt: chấp nhận tiền tố "sha256:", chữ hoa/thường, có hoặc không có dấu hai chấm.</li>
 *   <li>Nếu danh sách ghim rỗng, ủy quyền cho TrustManager mặc định của hệ thống.</li>
 * </ul>
 */
public class FingerprintTrustManager implements X509TrustManager {

    private final List<String> pinnedFingerprints;
    private final List<byte[]> pinnedDigestBytes;
    private final X509TrustManager defaultTrustManager;

    public FingerprintTrustManager(List<String> pinnedFingerprints) {
        this(pinnedFingerprints, getDefaultSystemTrustManager());
    }

    public FingerprintTrustManager(String... pinnedFingerprints) {
        this(pinnedFingerprints != null ? Arrays.asList(pinnedFingerprints) : Collections.emptyList(), getDefaultSystemTrustManager());
    }

    public FingerprintTrustManager(List<String> pinnedFingerprints, X509TrustManager defaultTrustManager) {
        this.defaultTrustManager = defaultTrustManager;
        this.pinnedFingerprints = new ArrayList<>();
        this.pinnedDigestBytes = new ArrayList<>();

        if (pinnedFingerprints != null) {
            for (String fp : pinnedFingerprints) {
                if (fp == null || fp.trim().isEmpty()) continue;
                String normalized = normalizeFingerprint(fp);
                if (!normalized.isEmpty()) {
                    try {
                        byte[] bytes = HexFormat.of().parseHex(normalized);
                        this.pinnedFingerprints.add("sha256:" + normalized);
                        this.pinnedDigestBytes.add(bytes);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Định dạng fingerprint không hợp lệ: " + fp, e);
                    }
                }
            }
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Chuỗi chứng chỉ rỗng từ máy chủ (Empty certificate chain)");
        }

        X509Certificate leafCert = chain[0];
        byte[] leafEncoded = leafCert.getEncoded();

        byte[] actualDigest;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            actualDigest = md.digest(leafEncoded);
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException("Thuật toán SHA-256 không khả dụng trong JRE", e);
        }

        String actualHex = HexFormat.of().formatHex(actualDigest).toLowerCase();

        // 1. Nếu có cấu hình danh sách Fingerprint ghim: kiểm tra đối chiếu bằng MessageDigest.isEqual
        if (!pinnedDigestBytes.isEmpty()) {
            boolean matched = false;
            for (byte[] expectedDigest : pinnedDigestBytes) {
                if (MessageDigest.isEqual(actualDigest, expectedDigest)) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                throw new CertificateException(String.format(
                        "Từ chối kết nối! Fingerprint chứng chỉ máy chủ không khớp. Pinned: %s | Thực tế: sha256:%s",
                        pinnedFingerprints, actualHex));
            }
            return;
        }

        // 2. Nếu không cấu hình ghim fingerprint: ủy quyền cho TrustManager mặc định của hệ thống
        if (defaultTrustManager != null) {
            defaultTrustManager.checkServerTrusted(chain, authType);
        } else {
            throw new CertificateException("Không có Fingerprint nào được ghim và không tìm thấy TrustManager mặc định!");
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (defaultTrustManager != null) {
            defaultTrustManager.checkClientTrusted(chain, authType);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        if (defaultTrustManager != null) {
            return defaultTrustManager.getAcceptedIssuers();
        }
        return new X509Certificate[0];
    }

    public List<String> getPinnedFingerprints() {
        return Collections.unmodifiableList(pinnedFingerprints);
    }

    /**
     * Chuẩn hóa chuỗi Fingerprint: xóa tiền tố sha256:, dấu hai chấm, dấu gạch nối, khoảng trắng và chuyển chữ thường.
     */
    public static String normalizeFingerprint(String fp) {
        if (fp == null) return "";
        String s = fp.trim().toLowerCase();
        if (s.startsWith("sha256:")) {
            s = s.substring(7).trim();
        }
        s = s.replace(":", "").replace("-", "").replace(" ", "");
        return s;
    }

    /**
     * Tính Fingerprint SHA-256 của một chứng chỉ X509
     */
    public static String calculateFingerprint(X509Certificate cert) throws CertificateEncodingException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            return "sha256:" + HexFormat.of().formatHex(digest).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng trong JRE", e);
        }
    }

    private static X509TrustManager getDefaultSystemTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
