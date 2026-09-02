package com.wuxibio.care.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReplayNonceStore {

    private final Map<String, Long> nonceExpirations = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;

    @Autowired
    public ReplayNonceStore(@Value("${app.security.replay.max-nonces:10000}") int maxEntries) {
        this(Clock.systemUTC(), maxEntries);
    }

    ReplayNonceStore(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = Math.max(100, maxEntries);
    }

    public boolean register(String principalKey, String nonce, long ttlMs) {
        purgeExpired();
        if (nonceExpirations.size() >= maxEntries) {
            purgeExpired();
            if (nonceExpirations.size() >= maxEntries) {
                return false;
            }
        }
        String key = principalKey + ":" + nonce;
        long expiresAt = clock.millis() + ttlMs;
        return nonceExpirations.putIfAbsent(key, expiresAt) == null;
    }

    private void purgeExpired() {
        long now = clock.millis();
        Iterator<Map.Entry<String, Long>> iterator = nonceExpirations.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }
}
