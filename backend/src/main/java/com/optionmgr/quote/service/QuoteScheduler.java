package com.optionmgr.quote.service;

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
 * FEAT-052 Q270: wakes at :00 and :20 on weekdays 10:00-16:20 New York and lets {@link QuoteFetchService#tick()}
 * decide (10:00..16:00 on the hour + 16:20, trading days only). Q147: one run right after startup when inside
 * the trading-day window, in the background (startup never waits, failures never stop it - rule 9). Disabled with
 * {@code optionmgr.quotes.fetch-enabled=false} (test and e2e profiles).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "optionmgr.quotes", name = "fetch-enabled", havingValue = "true", matchIfMissing = true)
public class QuoteScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuoteScheduler.class);

    private final QuoteFetchService fetches;
    private final TaskScheduler scheduler;

    public QuoteScheduler(QuoteFetchService fetches, TaskScheduler scheduler) {
        this.fetches = fetches;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fetchAfterStartup() {
        scheduler.schedule(() -> safely(fetches::runIfInStartupWindow), Instant.now());
    }

    @Scheduled(cron = "0 0,20 10-16 * * MON-FRI", zone = "America/New_York")
    public void tick() {
        safely(fetches::tick);
    }

    private void safely(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.error("Quote fetch could not be completed: {}", e.getClass().getSimpleName());
        }
    }
}
