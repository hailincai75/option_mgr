package com.optionmgr.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.calendar.domain.ExchangeHoliday;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FEAT-009 rule 5: what counts as a well-formed Finnhub response. */
class FinnhubHolidayParserTest {

    private final FinnhubHolidayParser parser = new FinnhubHolidayParser(new ObjectMapper());

    @Test
    void parsesEntriesIgnoringPostMarketExchangeAndTimezone() throws Exception {
        List<ExchangeHoliday> list = parser.parse("""
                {"data":[
                  {"eventName":"National Mourning Day","atDate":"2025-01-09","tradingHour":"","postMarket":""},
                  {"eventName":"Thanksgiving Day","atDate":"2026-11-27","tradingHour":"09:30-13:00","postMarket":"13:00-17:00"},
                  {"eventName":"Good Friday","atDate":"2027-03-26"}
                ],"exchange":"US","timezone":"America/New_York"}""");
        assertThat(list).containsExactly(
                new ExchangeHoliday(LocalDate.of(2025, 1, 9), "National Mourning Day", null),
                new ExchangeHoliday(LocalDate.of(2026, 11, 27), "Thanksgiving Day", "09:30-13:00"),
                new ExchangeHoliday(LocalDate.of(2027, 3, 26), "Good Friday", null));
        assertThat(list.get(0).closedAllDay()).isTrue();
        assertThat(list.get(1).closedAllDay()).isFalse();
    }

    @Test
    void emptyDataIsValid() throws Exception {
        assertThat(parser.parse("{\"data\":[]}")).isEmpty();
    }

    @Test
    void aDuplicatedDateKeepsTheFullDayClosure() throws Exception {
        assertThat(parser.parse("""
                {"data":[{"eventName":"A","atDate":"2026-11-27","tradingHour":"09:30-13:00"},
                         {"eventName":"B","atDate":"2026-11-27","tradingHour":""}]}"""))
                .containsExactly(new ExchangeHoliday(LocalDate.of(2026, 11, 27), "B", null));
    }

    @Test
    void malformedResponses() {
        assertInvalid(null, "empty body");
        assertInvalid("", "empty body");
        assertInvalid("<html>oops</html>", "not JSON");
        assertInvalid("{\"error\":\"You don't have access\"}", "missing data array");
        assertInvalid("[1,2]", "missing data array");
        assertInvalid("{\"data\":{}}", "missing data array");
        assertInvalid("{\"data\":[1]}", "data[0] is not an object");
        assertInvalid("{\"data\":[{\"eventName\":\"X\"}]}", "data[0].atDate missing");
        assertInvalid("{\"data\":[{\"eventName\":\"X\",\"atDate\":\"2026-02-30\"}]}", "data[0].atDate invalid");
        assertInvalid("{\"data\":[{\"eventName\":\"X\",\"atDate\":\"2026/11/26\"}]}", "data[0].atDate invalid");
    }

    private void assertInvalid(String body, String reason) {
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(FinnhubHolidayParser.InvalidResponseException.class)
                .hasMessage(reason);
    }
}
