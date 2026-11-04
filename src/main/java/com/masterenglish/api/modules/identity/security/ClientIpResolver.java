package com.masterenglish.api.modules.identity.security;

import com.masterenglish.api.common.security.IpAddressNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies = new HashSet<>();

    public ClientIpResolver(@Value("${app.security.trusted-proxy-addresses:}") String addresses) {
        if (addresses.isBlank()) {
            return;
        }
        for (String address : addresses.split(",", -1)) {
            String normalized = IpAddressNormalizer.normalize(address);
            if (normalized == null) {
                throw new IllegalArgumentException("Trusted proxy addresses must be IP literals.");
            }
            trustedProxies.add(normalized);
        }
    }

    public String resolve(HttpServletRequest request) {
        String peer = IpAddressNormalizer.normalize(request.getRemoteAddr());
        if (!trustedProxies.contains(peer)) {
            return peer;
        }
        String forwarded =
                String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")));
        String[] hops = forwarded.split(",", -1);
        // Stop at the first untrusted hop. Any values to its left are supplied by that hop.
        for (int i = hops.length - 1; i >= 0; i--) {
            String address = IpAddressNormalizer.normalize(hops[i]);
            if (address == null) {
                return peer;
            }
            if (!trustedProxies.contains(address)) {
                return address;
            }
        }
        return peer;
    }
}
