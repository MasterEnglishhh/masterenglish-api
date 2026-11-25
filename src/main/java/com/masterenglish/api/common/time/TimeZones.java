package com.masterenglish.api.common.time;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared IANA timezone resolver ensuring Java {@link ZoneId} names remain compatible with
 * PostgreSQL {@code AT TIME ZONE}. Raw UTC offsets (e.g. {@code +07:00}, {@code UTC+07:00}), legacy
 * {@code SystemV/*} strings, and POSIX-style US compatibility zones ({@code EST5EDT}, {@code
 * CST6CDT}, {@code MST7MDT}, {@code PST8PDT}, which IANA tzdb 2026d+ unlinked from {@code
 * America/*} with distinct pre-1967 history and which PostgreSQL {@code pg_timezone_names} omits)
 * are rejected. Exact IANA backward aliases (e.g. {@code Asia/Saigon}, {@code Europe/Kiev}, {@code
 * US/Eastern}) are canonicalized to modern IANA identifiers present in PostgreSQL tzdb.
 */
public final class TimeZones {

    private static final Map<String, String> LEGACY_ALIASES =
            Map.ofEntries(
                    Map.entry("Africa/Asmera", "Africa/Asmara"),
                    Map.entry("America/Argentina/ComodRivadavia", "America/Argentina/Catamarca"),
                    Map.entry("America/Buenos_Aires", "America/Argentina/Buenos_Aires"),
                    Map.entry("America/Catamarca", "America/Argentina/Catamarca"),
                    Map.entry("America/Cordoba", "America/Argentina/Cordoba"),
                    Map.entry("America/Fort_Wayne", "America/Indiana/Indianapolis"),
                    Map.entry("America/Godthab", "America/Nuuk"),
                    Map.entry("America/Indianapolis", "America/Indiana/Indianapolis"),
                    Map.entry("America/Jujuy", "America/Argentina/Jujuy"),
                    Map.entry("America/Knox_IN", "America/Indiana/Knox"),
                    Map.entry("America/Louisville", "America/Kentucky/Louisville"),
                    Map.entry("America/Mendoza", "America/Argentina/Mendoza"),
                    Map.entry("America/Rosario", "America/Argentina/Cordoba"),
                    Map.entry("Antarctica/South_Pole", "Antarctica/McMurdo"),
                    Map.entry("Asia/Ashkhabad", "Asia/Ashgabat"),
                    Map.entry("Asia/Calcutta", "Asia/Kolkata"),
                    Map.entry("Asia/Choibalsan", "Asia/Ulaanbaatar"),
                    Map.entry("Asia/Chungking", "Asia/Shanghai"),
                    Map.entry("Asia/Dacca", "Asia/Dhaka"),
                    Map.entry("Asia/Katmandu", "Asia/Kathmandu"),
                    Map.entry("Asia/Macao", "Asia/Macau"),
                    Map.entry("Asia/Rangoon", "Asia/Yangon"),
                    Map.entry("Asia/Saigon", "Asia/Ho_Chi_Minh"),
                    Map.entry("Asia/Thimbu", "Asia/Thimphu"),
                    Map.entry("Asia/Ujung_Pandang", "Asia/Makassar"),
                    Map.entry("Asia/Ulan_Bator", "Asia/Ulaanbaatar"),
                    Map.entry("Atlantic/Faeroe", "Atlantic/Faroe"),
                    Map.entry("Australia/ACT", "Australia/Sydney"),
                    Map.entry("Australia/LHI", "Australia/Lord_Howe"),
                    Map.entry("Australia/NSW", "Australia/Sydney"),
                    Map.entry("Australia/North", "Australia/Darwin"),
                    Map.entry("Australia/Queensland", "Australia/Brisbane"),
                    Map.entry("Australia/South", "Australia/Adelaide"),
                    Map.entry("Australia/Tasmania", "Australia/Hobart"),
                    Map.entry("Australia/Victoria", "Australia/Melbourne"),
                    Map.entry("Australia/West", "Australia/Perth"),
                    Map.entry("Brazil/Acre", "America/Rio_Branco"),
                    Map.entry("Brazil/DeNoronha", "America/Noronha"),
                    Map.entry("Brazil/East", "America/Sao_Paulo"),
                    Map.entry("Brazil/West", "America/Manaus"),
                    Map.entry("CET", "Europe/Brussels"),
                    Map.entry("Canada/Atlantic", "America/Halifax"),
                    Map.entry("Canada/Central", "America/Winnipeg"),
                    Map.entry("Canada/Eastern", "America/Toronto"),
                    Map.entry("Canada/Mountain", "America/Edmonton"),
                    Map.entry("Canada/Newfoundland", "America/St_Johns"),
                    Map.entry("Canada/Pacific", "America/Vancouver"),
                    Map.entry("Canada/Saskatchewan", "America/Regina"),
                    Map.entry("Canada/Yukon", "America/Whitehorse"),
                    Map.entry("Chile/Continental", "America/Santiago"),
                    Map.entry("Chile/EasterIsland", "Pacific/Easter"),
                    Map.entry("Cuba", "America/Havana"),
                    Map.entry("EET", "Europe/Athens"),
                    Map.entry("Egypt", "Africa/Cairo"),
                    Map.entry("Eire", "Europe/Dublin"),
                    Map.entry("Europe/Kiev", "Europe/Kyiv"),
                    Map.entry("Europe/Uzhgorod", "Europe/Kyiv"),
                    Map.entry("Europe/Zaporozhye", "Europe/Kyiv"),
                    Map.entry("GB", "Europe/London"),
                    Map.entry("GB-Eire", "Europe/London"),
                    Map.entry("GMT0", "GMT"),
                    Map.entry("Greenwich", "Etc/Greenwich"),
                    Map.entry("Hongkong", "Asia/Hong_Kong"),
                    Map.entry("Iceland", "Atlantic/Reykjavik"),
                    Map.entry("Iran", "Asia/Tehran"),
                    Map.entry("Israel", "Asia/Jerusalem"),
                    Map.entry("Jamaica", "America/Jamaica"),
                    Map.entry("Japan", "Asia/Tokyo"),
                    Map.entry("Kwajalein", "Pacific/Kwajalein"),
                    Map.entry("Libya", "Africa/Tripoli"),
                    Map.entry("MET", "Europe/Brussels"),
                    Map.entry("Mexico/BajaNorte", "America/Tijuana"),
                    Map.entry("Mexico/BajaSur", "America/Mazatlan"),
                    Map.entry("Mexico/General", "America/Mexico_City"),
                    Map.entry("NZ", "Pacific/Auckland"),
                    Map.entry("NZ-CHAT", "Pacific/Chatham"),
                    Map.entry("Navajo", "America/Denver"),
                    Map.entry("PRC", "Asia/Shanghai"),
                    Map.entry("Pacific/Enderbury", "Pacific/Kanton"),
                    Map.entry("Pacific/Ponape", "Pacific/Pohnpei"),
                    Map.entry("Pacific/Truk", "Pacific/Chuuk"),
                    Map.entry("Poland", "Europe/Warsaw"),
                    Map.entry("Portugal", "Europe/Lisbon"),
                    Map.entry("ROK", "Asia/Seoul"),
                    Map.entry("Singapore", "Asia/Singapore"),
                    Map.entry("Turkey", "Europe/Istanbul"),
                    Map.entry("UCT", "UTC"),
                    Map.entry("US/Alaska", "America/Anchorage"),
                    Map.entry("US/Aleutian", "America/Adak"),
                    Map.entry("US/Arizona", "America/Phoenix"),
                    Map.entry("US/Central", "America/Chicago"),
                    Map.entry("US/East-Indiana", "America/Indiana/Indianapolis"),
                    Map.entry("US/Eastern", "America/New_York"),
                    Map.entry("US/Hawaii", "Pacific/Honolulu"),
                    Map.entry("US/Indiana-Starke", "America/Indiana/Knox"),
                    Map.entry("US/Michigan", "America/Detroit"),
                    Map.entry("US/Mountain", "America/Denver"),
                    Map.entry("US/Pacific", "America/Los_Angeles"),
                    Map.entry("US/Samoa", "Pacific/Pago_Pago"),
                    Map.entry("Universal", "UTC"),
                    Map.entry("W-SU", "Europe/Moscow"),
                    Map.entry("WET", "Europe/Lisbon"),
                    Map.entry("Zulu", "UTC"));

    private static final Set<String> UNSUPPORTED_LEGACY_ZONES =
            Set.of("EST5EDT", "CST6CDT", "MST7MDT", "PST8PDT");

    private static final Set<String> AVAILABLE_ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

    private static final Set<String> CANONICAL_ZONES =
            AVAILABLE_ZONES.stream()
                    .filter(
                            z ->
                                    !LEGACY_ALIASES.containsKey(z)
                                            && !z.startsWith("SystemV/")
                                            && !UNSUPPORTED_LEGACY_ZONES.contains(z))
                    .collect(Collectors.toUnmodifiableSet());

    private TimeZones() {}

    /** True for exact untrimmed IANA names (canonical or known legacy alias) supported by PG. */
    public static boolean isSupportedInput(String raw) {
        return raw != null
                && !raw.isEmpty()
                && raw.equals(raw.trim())
                && resolveCanonical(raw).isPresent();
    }

    /** Resolves a trimmed named zone (or known backward alias) to its canonical IANA ID. */
    public static Optional<String> resolveCanonical(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()
                || trimmed.startsWith("SystemV/")
                || UNSUPPORTED_LEGACY_ZONES.contains(trimmed)) {
            return Optional.empty();
        }
        String mapped = LEGACY_ALIASES.getOrDefault(trimmed, trimmed);
        if (!CANONICAL_ZONES.contains(mapped)) {
            return Optional.empty();
        }
        return Optional.of(mapped);
    }

    public static Optional<ZoneId> resolveCanonicalZone(String raw) {
        return resolveCanonical(raw)
                .flatMap(
                        id -> {
                            try {
                                return Optional.of(ZoneId.of(id));
                            } catch (DateTimeException exception) {
                                return Optional.empty();
                            }
                        });
    }

    public static String requireCanonical(String raw) {
        return resolveCanonical(raw)
                .orElseThrow(
                        () -> new IllegalArgumentException("Unsupported IANA time zone: " + raw));
    }

    public static Set<String> canonicalZones() {
        return CANONICAL_ZONES;
    }

    public static Map<String, String> legacyAliases() {
        return LEGACY_ALIASES;
    }
}
