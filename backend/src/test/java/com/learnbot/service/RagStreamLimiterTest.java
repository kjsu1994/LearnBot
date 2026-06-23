package com.learnbot.service;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagStreamLimiterTest {
    @Test
    void closeReturnsPermitForReuse() {
        RuntimeTuningService tuning = mock(RuntimeTuningService.class);
        when(tuning.ollamaNumParallel()).thenReturn(1);
        RagStreamLimiter limiter = new RagStreamLimiter(tuning);
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");

        RagStreamLimiter.StreamPermit first = limiter.tryAcquire(user);
        assertThat(first).isNotNull();
        assertThat(limiter.tryAcquire(user)).isNull();

        first.close();
        RagStreamLimiter.StreamPermit second = limiter.tryAcquire(user);
        assertThat(second).isNotNull();
        second.close();
    }

    @Test
    void closeIsIdempotent() {
        RuntimeTuningService tuning = mock(RuntimeTuningService.class);
        when(tuning.ollamaNumParallel()).thenReturn(1);
        RagStreamLimiter limiter = new RagStreamLimiter(tuning);

        RagStreamLimiter.StreamPermit permit = limiter.tryAcquire(null);
        assertThat(permit).isNotNull();

        permit.close();
        permit.close();

        RagStreamLimiter.StreamPermit next = limiter.tryAcquire(null);
        assertThat(next).isNotNull();
        next.close();
    }

    @Test
    void reactorCancelReturnsPermit() {
        RuntimeTuningService tuning = mock(RuntimeTuningService.class);
        when(tuning.ollamaNumParallel()).thenReturn(1);
        RagStreamLimiter limiter = new RagStreamLimiter(tuning);
        AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");

        RagStreamLimiter.StreamPermit permit = limiter.tryAcquire(user);
        assertThat(permit).isNotNull();

        Disposable subscription = Mono.never()
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType -> permit.close())
                .subscribe();
        assertThat(limiter.tryAcquire(user)).isNull();

        subscription.dispose();

        RagStreamLimiter.StreamPermit next = limiter.tryAcquire(user);
        assertThat(next).isNotNull();
        next.close();
    }
}
