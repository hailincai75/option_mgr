package com.optionmgr.calendar.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * FEAT-009 rule 2 (Q120): fetch once right after startup (in the background, so startup never waits for the network)
 * and then every day at 08:00 New York. No retries: a failure waits for the next run. Disabled with
 * {@code optionmgr.finnhub.fetch-enabled=false} (test and e2e profiles: tests never call the real Finnhub).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "optionmgr.finnhub", name = "fetch-enabled", havingValue = "true", matchIfMissing = true)
public class HolidayFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(HolidayFetchScheduler.class);

    private final HolidayFetchService fetches;
    private final TaskScheduler scheduler;

    public HolidayFetchScheduler(HolidayFetchService fetches, TaskScheduler scheduler) {
        this.fetches = fetches;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fetchAfterStartup() {
        scheduler.schedule(this::fetchSafely, Instant.now());
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "America/New_York")
    public void fetchDaily() {
        fetchSafely();
    }

    private void fetchSafely() {
        try {
            fetches.fetchNow();
        } catch (RuntimeException e) {
            // Status could not be recorded (e.g. database problem); other features are unaffected.
            log.error("Market holiday fetch could not be completed: {}", e.getClass().getSimpleName());
        }
    }
}
