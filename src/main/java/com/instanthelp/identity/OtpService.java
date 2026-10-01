package com.instanthelp.identity;

import com.instanthelp.common.TooManyRequestsException;
import com.instanthelp.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OtpService {

    private final OtpChallengeRepository challenges;
    private final OtpSender sender;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpChallengeRepository challenges, OtpSender sender, AppProperties props) {
        this.challenges = challenges;
        this.sender = sender;
        this.props = props;
    }

    @Transactional
    public void request(String phone) {
        Instant now = Instant.now();
        long recent = challenges.countByPhoneAndCreatedAtAfter(phone, now.minus(Duration.ofHours(1)));
        if (recent >= props.otp().maxRequestsPerHour()) {
            throw new TooManyRequestsException("Too many code requests. Try again later.");
        }
        String fixed = props.otp().fixedCode();
        String code = StringUtils.hasText(fixed) ? fixed : String.format("%06d", random.nextInt(1_000_000));
        challenges.save(new OtpChallenge(phone, hash(phone, code), now.plus(props.otp().ttl())));
        sender.send(phone, code);
    }

    /**
     * Verifies and consumes the latest code. noRollbackFor is essential: the failed-attempt counter
     * must be committed even though we throw.
     */
    @Transactional(noRollbackFor = InvalidOtpException.class)
    public void verify(String phone, String code) {
        OtpChallenge challenge = challenges.findFirstByPhoneAndConsumedFalseOrderByCreatedAtDesc(phone)
                .orElseThrow(InvalidOtpException::new);
        if (challenge.getExpiresAt().isBefore(Instant.now())
                || challenge.getAttempts() >= props.otp().maxAttempts()) {
            throw new InvalidOtpException();
        }
        challenge.incrementAttempts();
        boolean matches = MessageDigest.isEqual(
                challenge.getCodeHash().getBytes(StandardCharsets.UTF_8),
                hash(phone, code).getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new InvalidOtpException();
        }
        challenge.markConsumed();
    }

    /** HMAC so a leaked table cannot be brute-forced offline without the server secret. */
    private String hash(String phone, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash OTP", e);
        }
    }
}
