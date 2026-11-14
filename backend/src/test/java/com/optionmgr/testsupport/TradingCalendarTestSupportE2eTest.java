package com.optionmgr.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.calendar.FakeFinnhub;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** FEAT-009 test support (e2e profile): simulated Finnhub fetch, settable clock, reset. */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.optionmgr.account.DefaultAccountMockMvc.class)
@ActiveProfiles({"test", "e2e"})
class TradingCalendarTestSupportE2eTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    Clock clock;

    @BeforeEach
    @AfterEach
    void reset() throws Exception {
        assertThat(call(post("/api/test-support/reset")).getResponse().getStatus()).isEqualTo(204);
    }

    private MvcResult call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode fetch(String requestJson) throws Exception {
        MvcResult r = call(post("/api/test-support/trading-calendar/fetch").contentType(MediaType.APPLICATION_JSON)
                .content(requestJson));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r);
    }

    private void setClock(String instant) throws Exception {
        assertThat(call(put("/api/test-support/clock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"now\":\"" + instant + "\"}")).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void clockIsSystemTimeUntilFixedAndBackAfterDeleteOrReset() throws Exception {
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        setClock("2026-11-23T15:00:00Z");
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-23T15:00:00Z"));
        assertThat(body(call(get("/api/option-positions"))).get("asOfDate").asText()).isEqualTo("2026-11-23");
        assertThat(call(delete("/api/test-support/clock")).getResponse().getStatus()).isEqualTo(204);
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        setClock("2026-11-23T15:00:00Z");
        reset();
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void simulatedFetchesRunTheRealProcessingAndResetClearsThem() throws Exception {
        setClock("2026-11-20T13:00:00Z");
        JsonNode ok = fetch("{\"httpStatus\":200,\"body\":" + FakeFinnhub.scenario() + "}");
        assertThat(ok.get("stale").asBoolean()).isFalse();
        assertThat(ok.get("lastAttemptResult").asText()).isEqualTo("SUCCESS");
        assertThat(ok.get("coveredYears").toString()).isEqualTo("[2023,2024,2025,2026,2027]");

        setClock("2026-11-21T13:00:00Z");
        JsonNode failed = fetch("{\"httpStatus\":500,\"body\":null}");
        assertThat(failed.get("warning").asText()).isEqualTo("休市日数据未更新（上次成功：2026-11-20 08:00 美东）");
        assertThat(fetch("{\"httpStatus\":200,\"body\":\"not json\"}").get("lastAttemptResult").asText())
                .isEqualTo("INVALID_RESPONSE");
        JsonNode days = body(call(get("/api/trading-calendar/non-trading-days")
                .param("from", "2025-01-09").param("to", "2025-01-09")));
        assertThat(days.get("days").get(0).get("source").asText()).isEqualTo("FINNHUB");

        reset();
        JsonNode fresh = body(call(get("/api/trading-calendar/status")));
        assertThat(fresh.get("lastAttemptResult").isNull()).isTrue();
        assertThat(fresh.get("stale").asBoolean()).isFalse();
        assertThat(fresh.get("coveredYears")).isEmpty();
        assertThat(fetch("{\"noKey\":true}").get("warning").asText())
                .isEqualTo("休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）");
    }

    @Test
    void fetchNeedsHttpStatusOrNoKey() throws Exception {
        MvcResult r = call(post("/api/test-support/trading-calendar/fetch").contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r).get("details").get(0).get("field").asText()).isEqualTo("httpStatus");
    }
}
