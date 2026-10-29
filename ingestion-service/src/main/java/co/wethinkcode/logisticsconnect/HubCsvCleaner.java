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
 * Per-cell rules:
 *   - trim padding; collapse internal whitespace (ASCII + Unicode) to one space
 *   - placeholders (N/A, unknown, -, ...) -> null
 *   - IDs -> UPPERCASE (no spaces)
 *   - provinces -> canonical Title Case (SA provinces mapped)
 *   - status/category/type -> lowercase snake_case tokens
 *   - boolean/flag columns -> "true" / "false" (unknown tokens preserved)
 *   - date-like columns -> ISO-8601 (no-op on current CSV; wired for extensions)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * DE-DUPLICATION STRATEGY (also logged at runtime for reviewability):
 *
 *  1. IDENTITY. A row's identity is its normalized hub_id. If absent, the
 *     fallback is normalized (name + province). This is how a human would
 *     recognize the same hub across exports — the ID when present, else the
 *     human-readable label.
 *
 *  2. EXACT DUPLICATES. Same identity + identical values after normalization
 *     → keep one, count the rest. Nothing to choose between them.
 *
 *  3. CONFLICTING DUPLICATES. Same identity, disagreeing fields → keep the
 *     row with the most non-null fields. Rationale: the export likely
 *     accumulated missing data over time, so the most-populated row is the
 *     most trustworthy. We do NOT merge because merging invents rows the
 *     source never had. Ties break on first occurrence (deterministic).
 *
 *  4. KEYLESS ROWS. No ID and no name → keyed by own content hash, so two
 *     identical keyless rows still collapse to one. Nothing is silently lost.
 *
 * This is deliberately conservative: keep ambiguous data, log every decision.
 * With a reliable last_updated column, step 3 would pick by recency instead.
 * ─────────────────────────────────────────────────────────────────────────
 */
final class HubCsvCleaner {

    private static final Logger LOG = Logger.getLogger(HubCsvCleaner.class.getName());

    private static final Set<String> PLACEHOLDERS = Set.of(
            "",
            "n/a", "na", "n.a",
            "null", "nil", "none", "nan", "undefined",
            "unknown", "unk", "unspecified",
            "-", "--", "---", "\u2014", "\u2013", "\u2212",
            "?", "??", "???",
            "tbd", "tba", "to be determined", "to be announced", "to be confirmed",
            "not set", "not provided", "no data", "empty",
            "not applicable", "not available");

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

    private static final Set<String> BOOLEAN_TRUE = Set.of(
            "y", "yes", "true", "t", "1", "1.0", "on",
            "active", "enabled", "operational", "available", "verified",
            "\u2611", "\u2713", "\u2714");

    private static final Set<String> BOOLEAN_FALSE = Set.of(
            "n", "no", "false", "f", "0", "0.0", "off",
            "inactive", "disabled", "not operational", "not available", "unverified",
            "\u2610", "\u2717", "\u2718");

    private HubCsvCleaner() {}

    static List<Map<String, String>> clean(String csv) {
        List<String[]> rows = parseCsv(stripBom(csv));
        if (rows.isEmpty()) return List.of();

        String[] headers = new String[rows.get(0).length];
        for (int i = 0; i < headers.length; i++) {
            headers[i] = normalizeHeader(rows.get(0)[i]);
        }

        List<Map<String, String>> records = new ArrayList<>();
        int fullyEmptyRows = 0;
        for (int r = 1; r < rows.size(); r++) {
            Map<String, String> rec = toRecord(headers, rows.get(r));
            if (rec.values().stream().anyMatch(v -> v != null)) {
                records.add(rec);
            } else {
                fullyEmptyRows++;
            }
        }

        List<Map<String, String>> deduped = dedupe(records);

        final int inputRows = rows.size() - 1;
        final int empty = fullyEmptyRows;
        final int kept = deduped.size();
        LOG.info(() -> String.format(
                "Cleaned hubs-global.csv: %d input rows -> %d records (empty_rows_dropped=%d)",
                inputRows, kept, empty));
        return deduped;
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
        String h = collapseWhitespace(raw == null ? "" : raw.replace("\uFEFF", ""));
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
        if (isIdentifierColumn(header))  return normalizeIdentifier(v);
        if (isProvinceColumn(header))    return canonicalProvince(v);
        if (isStatusOrCategory(header))  return v.toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (isBooleanColumn(header)) {
            String b = asBoolean(v);
            if (b != null) return b;
        }
        if (DateNormalizer.isDateColumn(header)) {
            String d = DateNormalizer.normalize(v);
            if (d != null) return d;
        }
        return v;
    }

    static String collapseWhitespace(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[\\p{Z}\\s]+", " ").trim();
    }

    /** Canonical form for IDs: trimmed, no internal spaces, upper case. */
    static String normalizeIdentifier(String v) {
        return collapseWhitespace(v).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static boolean isPlaceholder(String v) {
        if (v == null) return true;
        String key = v.toLowerCase(Locale.ROOT).trim();
        if (key.isEmpty()) return true;
        key = key.replaceAll("^[([{<]+", "").replaceAll("[)\\]}>]+$", "").trim();
        if (key.isEmpty()) return true;
        key = key.replaceAll("\\s*/\\s*", "/");
        while (key.endsWith(".") && key.length() > 1) key = key.substring(0, key.length() - 1);
        return PLACEHOLDERS.contains(key);
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

    static String asBoolean(String v) {
        String key = v.toLowerCase(Locale.ROOT);
        if (BOOLEAN_TRUE.contains(key))  return "true";
        if (BOOLEAN_FALSE.contains(key)) return "false";
        return null;
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
        if (h.startsWith("is_") || h.startsWith("has_") || h.endsWith("_flag")) return true;
        return h.equals("active")     || h.endsWith("_active")
                || h.equals("enabled")    || h.endsWith("_enabled")
                || h.equals("operational")|| h.endsWith("_operational")
                || h.equals("available")  || h.endsWith("_available")
                || h.equals("verified")   || h.endsWith("_verified");
    }

    // ---- dedup (see class-level comment for the strategy) ----------------

    static List<Map<String, String>> dedupe(List<Map<String, String>> records) {
        Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
        int exactDuplicates = 0;
        int conflictsResolved = 0;
        int keyless = 0;

        for (Map<String, String> rec : records) {
            String key = dedupeKey(rec);
            if (key.startsWith("content:")) keyless++;
            Map<String, String> existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, rec);
            } else if (existing.equals(rec)) {
                exactDuplicates++;
            } else {
                conflictsResolved++;
                // Most-populated wins; on a tie, first occurrence is kept.
                if (populatedCount(rec) > populatedCount(existing)) {
                    byKey.put(key, rec);
                }
            }
        }

        final int ed = exactDuplicates, cr = conflictsResolved, kl = keyless;
        final int kept = byKey.size(), input = records.size();
        LOG.info(() -> String.format(
                "Dedup: %d -> %d records (exact_dupes_dropped=%d, conflicts_resolved=%d, keyless_rows=%d)",
                input, kept, ed, cr, kl));
        return new ArrayList<>(byKey.values());
    }

    static String dedupeKey(Map<String, String> rec) {
        // 1. Explicit ID column
        for (String k : new String[]{"hub_id", "code", "hub_code", "id"}) {
            String v = rec.get(k);
            if (v != null && !v.isBlank()) return "id:" + v;
        }
        // 2. (name + province) fallback
        String name = firstNonNull(rec, "name", "hub_name", "sorting_center_name");
        if (name != null) {
            String province = firstNonNull(rec, "province", "region");
            return "name:" + name.toLowerCase(Locale.ROOT) + "|"
                    + (province == null ? "" : province.toLowerCase(Locale.ROOT));
        }
        // 3. No identity: content itself is the key, so identical keyless rows still dedupe
        return "content:" + rec;
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

    // ---- lookup helper used by the REST layer ---------------------------

    static Map<String, String> findById(List<Map<String, String>> hubs, String rawId) {
        String requested = normalizeIdentifier(rawId);
        for (Map<String, String> hub : hubs) {
            String id = firstNonNull(hub, "hub_id", "id", "code", "hub_code");
            if (requested.equals(id)) return hub;
        }
        return null;
    }
}