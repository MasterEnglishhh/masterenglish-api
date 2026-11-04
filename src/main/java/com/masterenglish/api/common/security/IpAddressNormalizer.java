package com.masterenglish.api.common.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** Normalizes optional IP metadata for PostgreSQL inet without resolving hostnames. */
public final class IpAddressNormalizer {
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

    private IpAddressNormalizer() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        String literal = value.strip();
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
            if (!literal.contains(":")) return null;
        }
        // Do not allow hostnames, network masks, zone IDs, ports or forwarded lists.
        boolean ipv6 = literal.contains(":");
        if (!(ipv6 ? IPV6.matcher(literal).matches() : IPV4.matcher(literal).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(literal).getHostAddress();
        } catch (UnknownHostException invalidLiteral) {
            // An invalid optional address must not abort an otherwise valid login.
            return null;
        }
    }
}
