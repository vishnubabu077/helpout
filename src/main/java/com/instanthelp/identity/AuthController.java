package com.instanthelp.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Auth", description = "Phone + one-time code sign-in. Returns a JWT access token.")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/otp/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request a one-time code",
            description = "Sends a code to the phone. With the local profile the code is fixed to 000000 and logged.")
    public void request(@Valid @RequestBody RequestOtpRequest body) {
        auth.requestOtp(body.phone());
    }

    @PostMapping("/otp/verify")
    @Operation(summary = "Verify the code and get an access token",
            description = "Creates the account on first sign-in. Use the token as 'Authorization: Bearer <token>'.")
    public TokenResponse verify(@Valid @RequestBody VerifyOtpRequest body) {
        return auth.verifyOtp(body.phone(), body.code());
    }
}
