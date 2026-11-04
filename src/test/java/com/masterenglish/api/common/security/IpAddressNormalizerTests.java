package com.masterenglish.api.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IpAddressNormalizerTests {
    @Test
    void normalizesBracketedAndBareIpv6ToTheSameNumericAddress() {
        assertThat(IpAddressNormalizer.normalize(" [2a06:98c0:3600::103] "))
                .isEqualTo("2a06:98c0:3600:0:0:0:0:103")
                .isEqualTo(IpAddressNormalizer.normalize("2a06:98c0:3600::103"));
        assertThat(IpAddressNormalizer.normalize("[::1]")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(IpAddressNormalizer.normalize("::ffff:192.0.2.1")).isEqualTo("192.0.2.1");
    }

    @Test
    void preservesValidIpv4AndIgnoresMissingOrInvalidMetadata() {
        assertThat(IpAddressNormalizer.normalize(" 192.0.2.42 ")).isEqualTo("192.0.2.42");
        assertThat(IpAddressNormalizer.normalize(null)).isNull();
        for (String invalid :
                new String[] {
                    "",
                    " ",
                    "localhost",
                    "example.com",
                    "127.1",
                    "999.1.1.1",
                    "192.0.2.1:443",
                    "[::1]:443",
                    "[::1",
                    "fe80::1%eth0",
                    "::1/128",
                    "192.0.2.1, 127.0.0.1",
                    "[192.0.2.1]",
                    "::::",
                    "deadbeef"
                }) {
            assertThat(IpAddressNormalizer.normalize(invalid))
                    .as("invalid IP metadata: %s", invalid)
                    .isNull();
        }
    }
}
