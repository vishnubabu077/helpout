package com.instanthelp.identity;

import java.util.UUID;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, UUID userId) {}
