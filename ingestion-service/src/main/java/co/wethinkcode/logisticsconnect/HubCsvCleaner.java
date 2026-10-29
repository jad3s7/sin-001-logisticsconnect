package co.wethinkcode.logisticsconnect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Cleans the messy legacy hubs-global.csv export.
 *
 * Casing rules (the focus of this stage):
 *   - *_id columns   -> UPPERCASE, whitespace stripped        (e.g. " h-501 "  -> "H-501")
 *   - province cols  -> canonical Title Case (SA provinces mapped explicitly)
 *   - status/type/   -> lowercase snake_case tokens           (e.g. "In Transit" -> "in_transit")
 *     category cols
 * Also trims padding, collapses internal whitespace, and normalises boolean-like
 * flags to "true"/"false" (the TODO mentions booleans alongside casing).
 */
final class HubCsvCleaner {

    private static final Logger LOG = Logger.getLogger(HubCsvCleaner.class.getName());

    private static final Set<String> PLACEHOLDERS = Set.of(
            "", "n/a", "na", "n.a.", "null", "none", "unknown", "-", "--", "?", "tbd");

    private static final Map<String, String> SA_PROVINCES = Map.ofEntries(
            Map.entry("gauteng", "Gauteng"),
            Map.entry("western cape", "Western Cape"),
            Map.entry("kwazulu natal", "KwaZulu-Natal"),
            Map.entry("kwazulu-natal", "KwaZulu-Natal"),
            Map.entry("kzn", "KwaZulu-Natal"),
            Map.entry("eastern cape", "Eastern Cape"),
            Map.entry("free state", "Free State"),
            Map.entry("limpopo", "Limpopo"),
            Map.entry("mpumalanga", "Mpumalanga"),
            Map.entry("north west", "North West"),
            Map.entry("northern cape", "Northern Cape"));

    private HubCsvCleaner() {}

    static List<Map<String, String>> clean(String csv) {
        List<String[]> rows = parseCsv(stripBom(csv));
        if (rows.isEmpty()) return List.of();

        String[] headers = new String[rows.get(0).length];
        for (int i = 0; i < headers.length; i++) {
            headers[i] = normalizeHeader(rows.get(0)[i]);
        }

        List<Map<String, String>> records = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            Map<String, String> rec = toRecord(headers, rows.get(r));
            if (rec.values().stream().anyMatch(v -> v != null)) records.add(rec);
        }

        List<Map<String, String>> out = dedupe(records);
        LOG.info(() -> "Cleaned hubs-global.csv: " + records.size()
                + " rows -> " + out.size() + " records");
        return out;
    }

    // ---- parsing ---------------------------------------------------------

    static List<String[]> parseCsv(String csv) {
        List<String[]> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { fields.add(field.toString()); field.setLength(0); }
            else if (c == '\n') {
                fields.add(field.toString()); field.setLength(0);
                rows.add(fields.toArray(new String[0])); fields.clear();
            } else if (c != '\r') field.append(c);
        }
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            rows.add(fields.toArray(new String[0]));
        }
        return rows;
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    // ---- header + value normalization ------------------------------------

    static String normalizeHeader(String raw) {
        String h = raw == null ? "" : raw.trim().replace("\uFEFF", "");
        h = h.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        h = h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return h.replaceAll("^_+|_+$", "");
    }

    static Map<String, String> toRecord(String[] headers, String[] values) {
        Map<String, String> rec = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            String raw = i < values.length ? values[i] : "";
            rec.put(headers[i], normalizeValue(headers[i], raw));
        }
        return rec;
    }

    static String normalizeValue(String header, String raw) {
        String v = collapseWhitespace(raw);
        if (isPlaceholder(v)) return null;
        if (isIdentifierColumn(header))  return v.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (isProvinceColumn(header))    return canonicalProvince(v);
        if (isStatusOrCategory(header))  return v.toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (isBooleanColumn(header))     { String b = asBoolean(v); if (b != null) return b; }
        return v;
    }

    private static String collapseWhitespace(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }

    private static boolean isPlaceholder(String v) {
        return PLACEHOLDERS.contains(v.toLowerCase(Locale.ROOT));
    }

    private static String canonicalProvince(String v) {
        String key = v.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ").trim();
        String mapped = SA_PROVINCES.get(key);
        return mapped != null ? mapped : titleCase(v);
    }

    private static String titleCase(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        boolean start = true;
        for (char c : v.toCharArray()) {
            if (Character.isWhitespace(c) || c == '-') { sb.append(c); start = true; }
            else if (start) { sb.append(Character.toUpperCase(c)); start = false; }
            else sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String asBoolean(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "y", "yes", "true", "t", "1" -> "true";
            case "n", "no", "false", "f", "0" -> "false";
            default -> null;
        };
    }

    private static boolean isIdentifierColumn(String h) {
        return h.equals("id") || h.endsWith("_id");
    }

    private static boolean isProvinceColumn(String h) {
        return h.contains("province") || h.contains("region") || h.contains("district");
    }

    private static boolean isStatusOrCategory(String h) {
        return h.contains("status") || h.contains("category")
                || h.equals("type") || h.endsWith("_type");
    }

    private static boolean isBooleanColumn(String h) {
        return h.startsWith("is_") || h.startsWith("has_")
                || h.endsWith("_flag") || h.contains("active");
    }

    // ---- dedup -----------------------------------------------------------

    private static List<Map<String, String>> dedupe(List<Map<String, String>> records) {
        Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
        for (Map<String, String> rec : records) {
            String key = dedupeKey(rec);
            if (key == null) key = "row:" + byKey.size();
            Map<String, String> existing = byKey.get(key);
            if (existing == null || populatedCount(rec) > populatedCount(existing)) {
                byKey.put(key, rec);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static String dedupeKey(Map<String, String> rec) {
        for (String k : new String[]{"hub_id", "code", "hub_code", "id"}) {
            String v = rec.get(k);
            if (v != null && !v.isBlank()) return "id:" + v;
        }
        String name = firstNonNull(rec, "name", "hub_name", "sorting_center_name");
        if (name != null) {
            String province = firstNonNull(rec, "province", "region");
            return "name:" + name.toLowerCase(Locale.ROOT) + "|"
                    + (province == null ? "" : province.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private static String firstNonNull(Map<String, String> rec, String... keys) {
        for (String k : keys) {
            String v = rec.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static int populatedCount(Map<String, String> rec) {
        int n = 0;
        for (String v : rec.values()) if (v != null && !v.isBlank()) n++;
        return n;
    }
}