package com.optionmgr.calendar.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.calendar.domain.ExchangeHoliday;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEAT-009: parses a Finnhub {@code /stock/market-holiday} body
 * ({@code {data:[{eventName, atDate, tradingHour, postMarket}], exchange, timezone}}). {@code exchange} /
 * {@code timezone} / {@code postMarket} are ignored. A date listed twice keeps the full-day closure if either entry is
 * one. Pure (no Spring).
 */
public final class FinnhubHolidayParser {

    /** The body is not a valid holiday response; the message is a short reason safe to store and show. */
    public static final class InvalidResponseException extends Exception {
        public InvalidResponseException(String reason) {
            super(reason);
        }
    }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final ObjectMapper mapper;

    public FinnhubHolidayParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<ExchangeHoliday> parse(String body) throws InvalidResponseException {
        if (body == null || body.isBlank()) {
            throw new InvalidResponseException("empty body");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InvalidResponseException("not JSON");
        }
        if (root == null || !root.isObject() || !root.path("data").isArray()) {
            throw new InvalidResponseException("missing data array");
        }
        Map<LocalDate, ExchangeHoliday> byDate = new LinkedHashMap<>();
        int i = 0;
        for (JsonNode item : root.get("data")) {
            ExchangeHoliday h = entry(item, i++);
            byDate.merge(h.date(), h, (a, b) -> a.closedAllDay() ? a : b);
        }
        return new ArrayList<>(byDate.values());
    }

    private static ExchangeHoliday entry(JsonNode item, int index) throws InvalidResponseException {
        if (!item.isObject()) {
            throw new InvalidResponseException("data[" + index + "] is not an object");
        }
        JsonNode at = item.get("atDate");
        if (at == null || !at.isTextual()) {
            throw new InvalidResponseException("data[" + index + "].atDate missing");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(at.asText().strip(), DATE);
        } catch (DateTimeParseException e) {
            throw new InvalidResponseException("data[" + index + "].atDate invalid");
        }
        JsonNode name = item.get("eventName");
        String eventName = name == null || name.isNull() ? "" : name.asText().strip();
        if (eventName.length() > 200) {
            eventName = eventName.substring(0, 200);
        }
        JsonNode hours = item.get("tradingHour");
        String tradingHour = hours == null || hours.isNull() ? null : hours.asText();
        if (tradingHour != null && tradingHour.strip().length() > 50) {
            throw new InvalidResponseException("data[" + index + "].tradingHour invalid");
        }
        return new ExchangeHoliday(date, eventName, tradingHour);
    }
}
