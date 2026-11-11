package com.optionmgr.common.time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Jackson deserializer for user-entered New York local date-times (FEAT-007 {@code occurredAtLocal}, and
 * FEAT-001 trade time). Uses {@link MarketTime#parseLocal} (STRICT). Failures raise an
 * {@code InvalidFormatException} carrying the field path, which the global handler turns into
 * {@code 400 BAD_REQUEST} with {@code details[].field}.
 *
 * <p>Usage: {@code @JsonDeserialize(using = MarketLocalDateTimeDeserializer.class) LocalDateTime occurredAtLocal}.
 */
public class MarketLocalDateTimeDeserializer extends StdScalarDeserializer<LocalDateTime> {

    public MarketLocalDateTimeDeserializer() {
        super(LocalDateTime.class);
    }

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (!p.hasToken(JsonToken.VALUE_STRING)) {
            return (LocalDateTime) ctxt.handleUnexpectedToken(LocalDateTime.class, p);
        }
        String text = p.getText();
        try {
            return MarketTime.parseLocal(text);
        } catch (DateTimeParseException e) {
            throw ctxt.weirdStringException(text, LocalDateTime.class,
                    "expected New York local date-time YYYY-MM-DDTHH:mm[:ss]");
        }
    }
}
