package com.instanthelp.identity;

import java.util.UUID;

public record MeResponse(UUID id, String phone, String displayName, Role role) {}
