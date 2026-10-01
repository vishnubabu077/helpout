package com.instanthelp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Otp otp, Task task, Matching matching, Outbox outbox) {

    public record Jwt(String secret, String issuer, Duration accessTokenTtl) {}

    public record Otp(String fixedCode, Duration ttl, int maxAttempts, int maxRequestsPerHour) {}

    public record Task(int minDurationMinutes, String defaultCurrency) {}

    /**
     * @param searchRadiusM    GEOSEARCH radius around the task
     * @param candidateCount   max candidates read from Redis per match attempt
     * @param offerTtl         how long a helper has to accept an offer
     * @param heartbeatTimeout a helper without a heartbeat for this long is gone (Redis TTL and Postgres freshness)
     * @param sweepInterval    how often the offer / heartbeat sweeper runs
     */
    public record Matching(int searchRadiusM, int candidateCount, Duration offerTtl, Duration heartbeatTimeout,
                           Duration sweepInterval) {}

    public record Outbox(Duration pollInterval, int batchSize) {}
}
