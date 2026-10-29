package co.wethinkcode.logisticsconnect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes date-like values to ISO-8601.
 *
 * Scope: this is a NO-OP on the current hubs-global.csv (it has no date columns).
 * It exists so that any date-like field introduced later — including the
 * "timestamp" carried by package-status-topic in stage 3 — is normalized by the
 * same pipeline instead of every service inventing its own format.
 *
 * Output form:
 *   - date-only     -> "uuuu-MM-dd"       (e.g. "2024-03-15")
 *   - date+time     -> UTC ISO instant    (e.g. "2024-03-15T10:15:00Z")
 *   - unparseable   -> returned untouched (never lose data)
 *
 * Ambiguity policy: for slash-separated dates where both parts are <= 12,
 * the day-first (dd/MM) convention is assumed, matching South African usage.
 * This is logged nowhere and silently applied; if the CSV ever carries US-format
 * dates, extend the pattern list with an explicit "MM/dd/uuuu" pattern and pick
 * per-column via the header.
 */
final class DateNormalizer {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            // ISO-ish
            DateTimeFormatter.ISO_LOCAL_DATE,                           // 2024-03-15
            DateTimeFormatter.ofPattern("uuuu/M/d", Locale.ENGLISH),    // 2024/3/15
            // Day-first (SA / EU)
            DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ENGLISH),    // 15/3/2024
            DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ENGLISH),    // 15-03-2024
            DateTimeFormatter.ofPattern("d.M.uuuu", Locale.ENGLISH),    // 15.03.2024
            // Month names
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),  // 15 Mar 2024
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH), // 15 March 2024
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH), // Mar 15, 2024
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH),// March 15, 2024
            DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH),  // Mar 15 2024
            DateTimeFormatter.ofPattern("MMMM d uuuu", Locale.ENGLISH)  // March 15 2024
    );

    private static final List<DateTimeFormatter> DATETIME_FORMATS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,                     // 2024-03-15T10:15:00Z / +02:00
            DateTimeFormatter.ISO_ZONED_DATE_TIME,                      // ...+02:00[Europe/Paris]
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,                      // 2024-03-15T10:15:00
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss",   Locale.ENGLISH),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm",      Locale.ENGLISH),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d/M/uuuu HH:mm:ss",     Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d/M/uuuu HH:mm",        Locale.ENGLISH)
    );

    private DateNormalizer() {}

    /**
     * Returns true if the (already-normalized) header names a date/timestamp field.
     * Deliberately conservative: skips suffixes like _by, _name, _status, _type, _code, _id
     * so e.g. "created_by" (a person) is not treated as a date column.
     */
    static boolean isDateColumn(String header) {
        if (header == null) return false;
        if (header.endsWith("_by") || header.endsWith("_name") || header.endsWith("_id")
                || header.endsWith("_status") || header.endsWith("_type")
                || header.endsWith("_category") || header.endsWith("_flag")
                || header.endsWith("_count") || header.endsWith("_code")) {
            return false;
        }
        return header.contains("date")
                || header.endsWith("_at")
                || header.endsWith("_on")
                || header.contains("timestamp")
                || header.contains("created")
                || header.contains("updated")
                || header.contains("modified");
    }

    static String normalize(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;

        // Datetimes contain ':', date-only strings never do.
        if (v.indexOf(':') >= 0) {
            String dt = tryParseDateTime(v);
            if (dt != null) return dt;
        }

        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f).toString();
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return raw; // unparseable: keep as-is
    }

    private static String tryParseDateTime(String v) {
        for (DateTimeFormatter f : DATETIME_FORMATS) {
            try {
                TemporalAccessor ta = f.parseBest(v,
                        OffsetDateTime::from, ZonedDateTime::from, LocalDateTime::from);
                OffsetDateTime odt;
                if (ta instanceof OffsetDateTime o) odt = o;
                else if (ta instanceof ZonedDateTime z) odt = z.toOffsetDateTime();
                else if (ta instanceof LocalDateTime l) odt = l.atOffset(ZoneOffset.UTC);
                else continue;
                return odt.toInstant().toString();
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }
}