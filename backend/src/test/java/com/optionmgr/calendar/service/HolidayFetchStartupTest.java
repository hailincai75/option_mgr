package com.optionmgr.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.optionmgr.calendar.FakeFinnhub;
import com.optionmgr.calendar.domain.FetchResult;
import com.optionmgr.calendar.domain.FetchStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FEAT-009 rule 2 / AC7 / AC9 through the test seam: {@code optionmgr.finnhub.fetch-enabled=true} turns on the
 * startup fetch in a test, {@code base-url} points at a local fake, {@code api-key} supplies a dummy key. Each
 * nested class is its own application context on its own in-memory database.
 */
class HolidayFetchStartupTest {

    static final FakeFinnhub FINNHUB = new FakeFinnhub();

    @AfterAll
    static void stop() {
        FINNHUB.close();
    }

    static FetchStatus awaitAttempt(TradingCalendarService calendar) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            FetchStatus s = calendar.status();
            if (s.lastAttemptResult() != null) {
                return s;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no fetch attempt after startup");
    }

    @Nested
    @SpringBootTest(properties = {"optionmgr.finnhub.fetch-enabled=true", "optionmgr.finnhub.api-key=test-key-123",
            "spring.datasource.url=jdbc:h2:mem:feat009-startup-key;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
    @ExtendWith(OutputCaptureExtension.class)
    class WithKey {

        @DynamicPropertySource
        static void fake(DynamicPropertyRegistry r) {
            FINNHUB.respond(200, FakeFinnhub.scenario());
            r.add("optionmgr.finnhub.base-url", FINNHUB::baseUrl);
        }

        @Autowired
        TradingCalendarService calendar;

        @Autowired
        ApplicationContext context;

        @Test
        void fetchesOnceAfterStartupWithTheKeyOnlyInTheHeader(CapturedOutput output) throws Exception {
            FetchStatus s = awaitAttempt(calendar);
            assertThat(s.lastAttemptResult()).isEqualTo(FetchResult.SUCCESS);
            assertThat(calendar.calendar().coveredYears()).containsExactly(2023, 2024, 2025, 2026, 2027);
            assertThat(FINNHUB.received()).isNotEmpty().allSatisfy(r -> {
                assertThat(r.token()).isEqualTo("test-key-123");
                assertThat(r.uri()).doesNotContain("test-key-123");
            });
            assertThat(output.getAll()).contains("Market holidays updated from Finnhub").doesNotContain("test-key-123");
            assertThat(context.getBeansOfType(HolidayFetchScheduler.class)).hasSize(1);
        }
    }

    @Nested
    @SpringBootTest(properties = {"optionmgr.finnhub.fetch-enabled=true",
            "spring.datasource.url=jdbc:h2:mem:feat009-startup-nokey;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
    class WithoutKey {

        @Autowired
        TradingCalendarService calendar;

        @Test
        void ac7_startsAndReportsNoKey() throws Exception {
            FetchStatus s = awaitAttempt(calendar);
            assertThat(s.lastAttemptResult()).isEqualTo(FetchResult.NO_KEY);
            assertThat(s.warning()).isEqualTo("休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）");
            assertThat(calendar.calendar().coveredYears()).isEmpty();
            assertThat(calendar.calendar().isTradingDay(java.time.LocalDate.of(2026, 11, 26))).isFalse();
        }
    }
}
