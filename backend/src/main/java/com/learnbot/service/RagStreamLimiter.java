package com.learnbot.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RagStreamLimiter {
    private final RuntimeTuningService runtimeTuningService;
    private final AtomicInteger globalCount = new AtomicInteger();
    private final Map<UUID, AtomicInteger> userCounts = new ConcurrentHashMap<>();

    public RagStreamLimiter(RuntimeTuningService runtimeTuningService) {
        this.runtimeTuningService = runtimeTuningService;
    }

    public StreamPermit tryAcquire(AppUser user) {
        UUID userId = user == null || user.id() == null ? new UUID(0L, 0L) : user.id();
        int globalLimit = Math.max(1, runtimeTuningService.ollamaNumParallel());
        int userLimit = globalLimit >= 4 ? 2 : 1;
        while (true) {
            int current = globalCount.get();
            if (current >= globalLimit) {
                return null;
            }
            if (globalCount.compareAndSet(current, current + 1)) {
                break;
            }
        }
        AtomicInteger userCount = userCounts.computeIfAbsent(userId, ignored -> new AtomicInteger());
        while (true) {
            int current = userCount.get();
            if (current >= userLimit) {
                releaseGlobal();
                return null;
            }
            if (userCount.compareAndSet(current, current + 1)) {
                break;
            }
        }
        return new StreamPermit(userId, userCount);
    }

    private void releaseGlobal() {
        globalCount.updateAndGet(current -> Math.max(0, current - 1));
    }

    private void releaseUser(UUID userId, AtomicInteger counter) {
        int next = counter.updateAndGet(current -> Math.max(0, current - 1));
        if (next == 0) {
            userCounts.remove(userId, counter);
        }
    }

    public final class StreamPermit implements AutoCloseable {
        private final UUID userId;
        private final AtomicInteger userCount;
        private boolean closed;

        private StreamPermit(UUID userId, AtomicInteger userCount) {
            this.userId = userId;
            this.userCount = userCount;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            releaseGlobal();
            releaseUser(userId, userCount);
        }
    }
}
