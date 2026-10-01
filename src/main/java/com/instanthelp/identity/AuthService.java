package com.instanthelp.identity;

import com.instanthelp.common.ForbiddenException;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final OtpService otp;
    private final AppUserRepository users;
    private final TokenService tokens;

    public AuthService(OtpService otp, AppUserRepository users, TokenService tokens) {
        this.otp = otp;
        this.users = users;
        this.tokens = tokens;
    }

    public void requestOtp(String phone) {
        otp.request(phone);
    }

    /** Not transactional on purpose: OtpService.verify commits failed attempts on its own. */
    public TokenResponse verifyOtp(String phone, String code) {
        otp.verify(phone, code);
        AppUser user = users.findByPhone(phone).orElseGet(() -> users.save(new AppUser(phone)));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Account is not active");
        }
        String token = tokens.accessToken(user.getId(), Set.of(user.getRole().name()));
        return new TokenResponse(token, "Bearer", tokens.accessTokenTtlSeconds(), user.getId());
    }
}
