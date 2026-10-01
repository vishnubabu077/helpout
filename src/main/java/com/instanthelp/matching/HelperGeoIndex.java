package com.instanthelp.matching;

import com.instanthelp.config.AppProperties;
import com.instanthelp.helper.AvailabilityStatus;
import com.instanthelp.helper.HelperAvailabilityEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands.DistanceUnit;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * The Redis read model: AVAILABLE helpers in one GEO set per category plus a small hash per helper.
 * It is only used to find candidates. It can be wiped and rebuilt from Postgres at any time, and nothing
 * here decides who gets a task (that is the conditional UPDATE in Postgres).
 */
@Component
public class HelperGeoIndex {

    private static final Logger log = LoggerFactory.getLogger(HelperGeoIndex.class);

    /** GEOADD rejects latitudes beyond the Web Mercator limit. */
    private static final double MAX_GEO_LATITUDE = 85.05112878;
    /** The version tombstone outlives the hash so a late, older event cannot resurrect a removed helper. */
    private static final long VERSION_TTL_SECONDS = Duration.ofHours(1).toSeconds();

    private final StringRedisTemplate redis;
    private final AppProperties props;
    private final DefaultRedisScript<Long> applyScript;

    public HelperGeoIndex(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
        this.applyScript = new DefaultRedisScript<>();
        this.applyScript.setLocation(new ClassPathResource("redis/apply-helper-event.lua"));
        this.applyScript.setResultType(Long.class);
    }

    public static String geoKey(String categoryCode) {
        return "helpers:geo:" + categoryCode;
    }

    public static String hashKey(UUID availabilityId) {
        return "helper:" + availabilityId;
    }

    static String versionKey(UUID availabilityId) {
        return "helper:" + availabilityId + ":version";
    }

    /** What the matcher needs from the per-helper hash. */
    public record CandidateInfo(UUID helperId, long version, int maxDistanceM) {}

    public record Candidate(UUID availabilityId, double distanceM) {}

    /** Applies a Kafka event. Returns false when it was stale or a duplicate (no-op). */
    public boolean apply(HelperAvailabilityEvent event) {
        return apply(event, props.matching().heartbeatTimeout(), false);
    }

    /**
     * @param hashTtl TTL for the helper hash (refreshed on every applied location event)
     * @param repair  rebuild mode: also restore a missing hash at the already-applied version
     */
    public boolean apply(HelperAvailabilityEvent event, Duration hashTtl, boolean repair) {
        boolean index = event.status() == AvailabilityStatus.AVAILABLE;
        if (index && Math.abs(event.latitude()) > MAX_GEO_LATITUDE) {
            log.warn("Helper {} is at latitude {}, which Redis GEO cannot index; treating as unavailable",
                    event.helperId(), event.latitude());
            index = false;
        }

        List<String> keys = new ArrayList<>();
        keys.add(hashKey(event.availabilityId()));
        keys.add(versionKey(event.availabilityId()));
        event.categories().forEach(c -> keys.add(geoKey(c)));

        Long applied = redis.execute(applyScript, keys,
                Long.toString(event.version()),
                index ? "UPSERT" : "REMOVE",
                Long.toString(Math.max(1, hashTtl.toSeconds())),
                event.availabilityId().toString(),
                plain(event.longitude()),
                plain(event.latitude()),
                event.helperId().toString(),
                event.status().name(),
                Integer.toString(event.maxDistance()),
                String.join(",", event.categories()),
                Long.toString(VERSION_TTL_SECONDS),
                repair ? "1" : "0");
        return applied != null && applied == 1L;
    }

    /** Nearest first, within radiusM metres of the point. */
    public List<Candidate> search(String categoryCode, double latitude, double longitude, int radiusM, int count) {
        GeoResults<GeoLocation<String>> results = redis.opsForGeo().search(
                geoKey(categoryCode),
                GeoReference.fromCoordinate(longitude, latitude),
                new Distance(radiusM, DistanceUnit.METERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(count));
        List<Candidate> candidates = new ArrayList<>();
        if (results == null) {
            return candidates;
        }
        for (GeoResult<GeoLocation<String>> r : results) {
            candidates.add(new Candidate(UUID.fromString(r.getContent().getName()), r.getDistance().getValue()));
        }
        return candidates;
    }

    /** Empty when the hash expired (the helper stopped heart-beating) or was removed. */
    public Optional<CandidateInfo> info(UUID availabilityId) {
        Map<Object, Object> hash = redis.opsForHash().entries(hashKey(availabilityId));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CandidateInfo(
                    UUID.fromString((String) hash.get("helperId")),
                    Long.parseLong((String) hash.get("version")),
                    Integer.parseInt((String) hash.get("maxDistance"))));
        } catch (RuntimeException e) {
            log.warn("Corrupt Redis hash for availability {}: {}", availabilityId, e.toString());
            return Optional.empty();
        }
    }

    /** Lazy cleanup of a GEO member whose hash expired, and the synchronous removal after a reservation. */
    public void removeMember(String categoryCode, UUID availabilityId) {
        redis.opsForZSet().remove(geoKey(categoryCode), availabilityId.toString());
    }

    public void removeFromAll(Collection<String> categoryCodes, UUID availabilityId) {
        categoryCodes.forEach(c -> removeMember(c, availabilityId));
    }

    public List<String> members(String categoryCode) {
        var members = redis.opsForZSet().range(geoKey(categoryCode), 0, -1);
        return members == null ? List.of() : List.copyOf(members);
    }

    public boolean hashExists(UUID availabilityId) {
        return Boolean.TRUE.equals(redis.hasKey(hashKey(availabilityId)));
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).toPlainString();
    }
}
