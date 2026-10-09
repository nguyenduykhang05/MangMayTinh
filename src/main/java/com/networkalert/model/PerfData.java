package com.networkalert.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Đại diện cho một chỉ số đo lường hiệu năng theo chuẩn Nagios Performance Data (ERRATA §E9.1):
 * Định dạng: {@code 'label'=value[UOM];[warn];[crit];[min];[max]}
 */
public class PerfData {

    private final String label;
    private final double value;
    private final String unit;
    private final Double warn;
    private final Double crit;
    private final Double min;
    private final Double max;

    public PerfData(String label, double value, String unit, Double warn, Double crit, Double min, Double max) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("Nhãn PerfData (label) không được để trống!");
        }
        this.label = label.trim();
        this.value = value;
        this.unit = unit != null ? unit.trim() : "";
        this.warn = warn;
        this.crit = crit;
        this.min = min;
        this.max = max;
    }

    public PerfData(String label, double value, String unit) {
        this(label, value, unit, null, null, null, null);
    }

    public PerfData(String label, double value, String unit, Double warn, Double crit) {
        this(label, value, unit, warn, crit, null, null);
    }

    public static PerfData of(String label, double value, String unit) {
        return new PerfData(label, value, unit);
    }

    public static PerfData of(String label, double value, String unit, Double warn, Double crit) {
        return new PerfData(label, value, unit, warn, crit);
    }

    public static PerfData of(String label, double value, String unit, Double warn, Double crit, Double min, Double max) {
        return new PerfData(label, value, unit, warn, crit, min, max);
    }

    public String getLabel() { return label; }
    public double getValue() { return value; }
    public String getUnit() { return unit; }
    public Double getWarn() { return warn; }
    public Double getCrit() { return crit; }
    public Double getMin() { return min; }
    public Double getMax() { return max; }

    /**
     * Xuất chuỗi PerfData chuẩn Nagios. Ví dụ: {@code 'rta'=25.0ms;150;600;0;}
     */
    public String toNagiosString() {
        StringBuilder sb = new StringBuilder();
        if (label.contains(" ")) {
            sb.append("'").append(label).append("'");
        } else {
            sb.append(label);
        }
        sb.append("=").append(formatValue(value)).append(unit);

        boolean hasWarn = (warn != null);
        boolean hasCrit = (crit != null);
        boolean hasMin = (min != null);
        boolean hasMax = (max != null);

        if (hasWarn || hasCrit || hasMin || hasMax) {
            sb.append(";").append(hasWarn ? formatValue(warn) : "");
            if (hasCrit || hasMin || hasMax) {
                sb.append(";").append(hasCrit ? formatValue(crit) : "");
                if (hasMin || hasMax) {
                    sb.append(";").append(hasMin ? formatValue(min) : "");
                    if (hasMax) {
                        sb.append(";").append(formatValue(max));
                    }
                }
            }
        }
        return sb.toString();
    }

    private String formatValue(double v) {
        if (v == (long) v) {
            return String.format(Locale.US, "%d", (long) v);
        }
        return String.format(Locale.US, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * Giải mã chuỗi phân tách bởi khoảng trắng chứa nhiều PerfData
     */
    public static List<PerfData> parseMultiple(String input) {
        List<PerfData> result = new ArrayList<>();
        if (input == null || input.trim().isEmpty()) {
            return result;
        }

        String[] tokens = input.trim().split("\\s+");
        for (String token : tokens) {
            PerfData pd = parse(token);
            if (pd != null) {
                result.add(pd);
            }
        }
        return result;
    }

    /**
     * Giải mã một chuỗi PerfData chuẩn Nagios
     */
    public static PerfData parse(String token) {
        if (token == null || !token.contains("=")) {
            return null;
        }
        try {
            int eqIdx = token.indexOf('=');
            String label = token.substring(0, eqIdx).trim();
            if (label.startsWith("'") && label.endsWith("'") && label.length() >= 2) {
                label = label.substring(1, label.length() - 1);
            }

            String remainder = token.substring(eqIdx + 1).trim();
            String[] parts = remainder.split(";", -1);

            // parts[0] là value + unit
            String valAndUnit = parts[0];
            int unitIdx = 0;
            while (unitIdx < valAndUnit.length() && (Character.isDigit(valAndUnit.charAt(unitIdx)) || valAndUnit.charAt(unitIdx) == '.' || valAndUnit.charAt(unitIdx) == '-')) {
                unitIdx++;
            }
            double val = Double.parseDouble(valAndUnit.substring(0, unitIdx));
            String unit = valAndUnit.substring(unitIdx);

            Double warn = (parts.length > 1 && !parts[1].isEmpty()) ? Double.parseDouble(parts[1]) : null;
            Double crit = (parts.length > 2 && !parts[2].isEmpty()) ? Double.parseDouble(parts[2]) : null;
            Double min = (parts.length > 3 && !parts[3].isEmpty()) ? Double.parseDouble(parts[3]) : null;
            Double max = (parts.length > 4 && !parts[4].isEmpty()) ? Double.parseDouble(parts[4]) : null;

            return new PerfData(label, val, unit, warn, crit, min, max);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public String toString() {
        return toNagiosString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PerfData)) return false;
        PerfData perfData = (PerfData) o;
        return Double.compare(perfData.value, value) == 0 &&
                Objects.equals(label, perfData.label) &&
                Objects.equals(unit, perfData.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, value, unit);
    }
}
