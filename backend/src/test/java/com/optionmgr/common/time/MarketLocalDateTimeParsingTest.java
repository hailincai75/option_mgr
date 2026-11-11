package com.optionmgr.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** BUG-001: strict parsing of user-entered New York local date-times. */
class MarketLocalDateTimeParsingTest {

    record Probe(@JsonDeserialize(using = MarketLocalDateTimeDeserializer.class) LocalDateTime at) {
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "2026-11-02T10:00, 2026-11-02T10:00",
            "2026-11-02T10:00:30, 2026-11-02T10:00:30",
            "2028-02-29T09:30, 2028-02-29T09:30",
            "2026-12-31T23:59:59, 2026-12-31T23:59:59",
            "2026-03-08T02:30, 2026-03-08T02:30"})
    void acceptsMinuteOrSecondPrecision(String text, String expected) {
        assertThat(MarketTime.parseLocal(text)).isEqualTo(LocalDateTime.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-02-30T10:00", "2026-04-31T10:00", "2027-02-29T10:00", "2026-13-01T10:00", "2026-00-10T10:00",
            "2026-11-02T24:00", "2026-11-02T10:60", "2026-11-02T10:00:60",
            "2026-11-02T10:00Z", "2026-11-02T10:00:00-05:00", "2026-11-02T10:00:00.5",
            "2026-11-02 10:00", "2026-11-02", "2026-11-2T10:00", "11/02/2026 10:00 AM", "abc", ""})
    void rejectsMalformedOrNonExistentValues(String text) {
        assertThatThrownBy(() -> MarketTime.parseLocal(text)).isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void deserializerParsesValidValueAndNull() throws Exception {
        assertThat(mapper.readValue("{\"at\":\"2026-11-02T10:00\"}", Probe.class).at())
                .isEqualTo(LocalDateTime.parse("2026-11-02T10:00"));
        assertThat(mapper.readValue("{\"at\":null}", Probe.class).at()).isNull();
    }

    @Test
    void deserializerReportsFieldPathForImpossibleDate() {
        assertThatThrownBy(() -> mapper.readValue("{\"at\":\"2026-02-30T10:00\"}", Probe.class))
                .isInstanceOfSatisfying(InvalidFormatException.class,
                        e -> assertThat(e.getPath().get(0).getFieldName()).isEqualTo("at"));
    }

    @Test
    void deserializerRejectsNonStringToken() {
        assertThatThrownBy(() -> mapper.readValue("{\"at\":20261102}", Probe.class))
                .isInstanceOfSatisfying(MismatchedInputException.class,
                        e -> assertThat(e.getPath().get(0).getFieldName()).isEqualTo("at"));
    }
}
