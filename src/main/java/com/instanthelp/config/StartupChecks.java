package com.instanthelp.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Fail fast on unsafe configuration. */
@Component
class StartupChecks {

    StartupChecks(AppProperties props, Environment env) {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        if (prod && StringUtils.hasText(props.otp().fixedCode())) {
            throw new IllegalStateException("app.otp.fixed-code must not be set when the 'prod' profile is active");
        }
    }
}
