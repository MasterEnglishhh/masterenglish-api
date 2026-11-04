package com.masterenglish.api.modules.identity.service;

import java.util.Optional;
import java.util.function.Supplier;

public interface RateLimiterService {

    <T> Optional<T> verify(
            String email,
            String clientIp,
            boolean resetEmailOnSuccess,
            Supplier<Optional<T>> verification);
}
