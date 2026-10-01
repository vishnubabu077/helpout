package com.instanthelp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Otp otp, Task task) {

    public record Jwt(String secret, String issuer, Duration accessTokenTtl) {}

    public record Otp(String fixedCode, Duration ttl, int maxAttempts, int maxRequestsPerHour) {}

    public record Task(int minDurationMinutes, String defaultCurrency) {}
}
