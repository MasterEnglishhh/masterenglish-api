package com.masterenglish.api.modules.identity.service;

public interface RateLimiterService {

    void checkLoginRateLimit(String email, String clientIp);

    void recordLoginFailure(String email, String clientIp);

    void resetLoginFailure(String email);
}
