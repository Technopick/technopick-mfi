package ke.co.technopick.mfi.core.member.application;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ke.co.technopick.mfi.core.common.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Requests per member per minute on the member API (ADR-0033), counted on this server. With
 * several servers behind a load balancer a member gets at most this many per server; payment
 * prompts have their own limit, counted in the database across all servers.
 */
@Component
public class MemberRateLimiter {

    private record Window(long minute, int count) { }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int perMinute;
    private final Clock clock;

    public MemberRateLimiter(@Value("${mfi.member.requests-per-minute:60}") int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    public void check(String member) {
        long minute = clock.millis() / 60_000;
        Window w = windows.merge(member, new Window(minute, 1),
                (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
        if (w.count() > perMinute) {
            throw new TooManyRequestsException("too many requests; please wait a minute", 60 - (clock.millis() / 1000) % 60);
        }
        if (windows.size() > 100_000) {
            windows.values().removeIf(x -> x.minute() < minute); // drop finished windows; never more than one per active member
        }
    }
}
