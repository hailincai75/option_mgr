package com.optionmgr.quote.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.quote.domain.CboeChain;
import com.optionmgr.quote.domain.CboeOption;
import com.optionmgr.quote.domain.FinnhubQuote;
import com.optionmgr.quote.domain.OccCode;
import com.optionmgr.quote.domain.YahooChain;
import com.optionmgr.quote.domain.YahooOption;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FEAT-016: response text to domain objects. Numbers are read as exact {@link BigDecimal}s (never through
 * {@code double}). A malformed single option entry only loses that entry's bad fields; a malformed response is an
 * {@link InvalidResponseException}. No Spring.
 */
public final class QuoteParsers {

    /** The response does not have the expected shape; the message is short and safe to store. */
    public static final class InvalidResponseException extends Exception {
        public InvalidResponseException(String message) {
            super(message);
        }
    }

    private static final DateTimeFormatter CBOE_TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    /**
     * CBOE {@code {timestamp, data:{current_price, bid, ask, prev_day_close, options:[...]}}}. {@code timestamp} is
     * {@code yyyy-MM-dd HH:mm:ss} New York local (the CBOE snapshot time); an ISO date-time with offset is accepted
     * too. Option codes whose last 15 characters are not YYMMDD + C/P + 8 digits are skipped.
     */
    public CboeChain parseCboe(String body) throws InvalidResponseException {
        JsonNode root = tree(body);
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            throw new InvalidResponseException("missing data object");
        }
        JsonNode options = data.get("options");
        if (options == null || !options.isArray()) {
            throw new InvalidResponseException("missing data.options array");
        }
        Instant timestamp = timestamp(root.get("timestamp"));
        List<CboeOption> parsed = new ArrayList<>();
        for (JsonNode o : options) {
            if (!o.isObject() || o.get("option") == null || !o.get("option").isTextual()) {
                continue;
            }
            Optional<OccCode> code = OccCode.parse(o.get("option").asText());
            code.ifPresent(c -> parsed.add(new CboeOption(c, dec(o, "bid"), dec(o, "ask"), dec(o, "last_trade_price"),
                    dec(o, "theo"), dec(o, "iv"), dec(o, "delta"), dec(o, "gamma"), dec(o, "theta"), dec(o, "vega"),
                    dec(o, "rho"), whole(o, "volume"), whole(o, "open_interest"))));
        }
        return new CboeChain(timestamp, dec(data, "current_price"), dec(data, "bid"), dec(data, "ask"),
                dec(data, "prev_day_close"), parsed);
    }

    /** Finnhub {@code {c, pc, t, ...}}: {@code c} must be &gt; 0 (unknown symbols answer all zeros). */
    public FinnhubQuote parseFinnhub(String body) throws InvalidResponseException {
        JsonNode root = tree(body);
        BigDecimal c = dec(root, "c");
        if (c == null || c.signum() <= 0) {
            throw new InvalidResponseException("no price");
        }
        Long t = whole(root, "t");
        return new FinnhubQuote(c, dec(root, "pc"), t == null || t <= 0 ? null : Instant.ofEpochSecond(t));
    }

    /**
     * FEAT-052 Yahoo {@code /v7/finance/options}: {@code optionChain.result} a non-empty array,
     * {@code optionChain.error} null or missing, {@code result[0].quote} an object with a positive integer
     * {@code regularMarketTime} (Unix seconds) - otherwise invalid. Contracts come from {@code options[0].calls} and
     * {@code puts}; a malformed single field is missing, an unparseable {@code contractSymbol} skips the entry.
     */
    public YahooChain parseYahoo(String body) throws InvalidResponseException {
        JsonNode root = tree(body);
        JsonNode chain = root.get("optionChain");
        if (chain == null || !chain.isObject()) {
            throw new InvalidResponseException("missing optionChain object");
        }
        JsonNode error = chain.get("error");
        if (error != null && !error.isNull()) {
            throw new InvalidResponseException("optionChain.error");
        }
        JsonNode result = chain.get("result");
        if (result == null || !result.isArray() || result.isEmpty() || !result.get(0).isObject()) {
            throw new InvalidResponseException("empty optionChain.result");
        }
        JsonNode r0 = result.get(0);
        JsonNode quote = r0.get("quote");
        if (quote == null || !quote.isObject()) {
            throw new InvalidResponseException("missing quote object");
        }
        Long time = nonNegativeWhole(quote, "regularMarketTime");
        if (time == null || time <= 0) {
            throw new InvalidResponseException("missing quote.regularMarketTime");
        }
        List<LocalDate> dates = new ArrayList<>();
        JsonNode exp = r0.get("expirationDates");
        if (exp != null && exp.isArray()) {
            for (JsonNode e : exp) {
                BigDecimal d = e.isNumber() ? e.decimalValue() : null;
                Long s = integral(d);
                if (s != null) {
                    dates.add(LocalDate.ofInstant(Instant.ofEpochSecond(s), ZoneOffset.UTC));
                }
            }
        }
        List<YahooOption> parsed = new ArrayList<>();
        JsonNode options = r0.get("options");
        if (options != null && options.isArray() && !options.isEmpty() && options.get(0).isObject()) {
            for (String side : List.of("calls", "puts")) {
                JsonNode list = options.get(0).get(side);
                if (list == null || !list.isArray()) {
                    continue;
                }
                for (JsonNode o : list) {
                    if (!o.isObject() || o.get("contractSymbol") == null || !o.get("contractSymbol").isTextual()) {
                        continue;
                    }
                    OccCode.parse(o.get("contractSymbol").asText()).ifPresent(c -> parsed.add(new YahooOption(c,
                            dec(o, "bid"), dec(o, "ask"), dec(o, "lastPrice"), dec(o, "impliedVolatility"),
                            nonNegativeWhole(o, "volume"), nonNegativeWhole(o, "openInterest"))));
                }
            }
        }
        return new YahooChain(Instant.ofEpochSecond(time), dec(quote, "regularMarketPrice"), dec(quote, "bid"),
                dec(quote, "ask"), dec(quote, "regularMarketPreviousClose"), dates, parsed);
    }

    /**
     * FEAT-052 getcrumb body: after stripping, 1-64 visible characters without {@code <}, <code>{</code> or white
     * space; otherwise empty (not a crumb, e.g. an HTML or JSON error page).
     */
    public static Optional<String> yahooCrumb(String body) {
        if (body == null) {
            return Optional.empty();
        }
        String c = body.strip();
        if (c.isEmpty() || c.length() > 64) {
            return Optional.empty();
        }
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch <= 0x20 || ch >= 0x7f || ch == '<' || ch == '{') {
                return Optional.empty();
            }
        }
        return Optional.of(c);
    }

    private JsonNode tree(String body) throws InvalidResponseException {
        if (body == null || body.isBlank()) {
            throw new InvalidResponseException("empty body");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InvalidResponseException("not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidResponseException("not a JSON object");
        }
        return root;
    }

    static Instant timestamp(JsonNode n) throws InvalidResponseException {
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new InvalidResponseException("missing timestamp");
        }
        String text = n.asText().strip();
        try {
            return MarketTime.toInstant(LocalDateTime.parse(text, CBOE_TIMESTAMP));
        } catch (DateTimeParseException ignored) {
            // try the other accepted forms
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // try ISO local
        }
        try {
            return MarketTime.toInstant(LocalDateTime.parse(text));
        } catch (DateTimeParseException e) {
            throw new InvalidResponseException("invalid timestamp");
        }
    }

    private static BigDecimal dec(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isTextual()) {
            try {
                return new BigDecimal(n.asText().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** FEAT-052: an integer &ge; 0, else null (non-integer or negative = missing). */
    private static Long nonNegativeWhole(JsonNode parent, String field) {
        Long v = integral(dec(parent, field));
        return v == null || v < 0 ? null : v;
    }

    private static Long integral(BigDecimal d) {
        if (d == null) {
            return null;
        }
        try {
            return d.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Long whole(JsonNode parent, String field) {
        BigDecimal d = dec(parent, field);
        if (d == null) {
            return null;
        }
        try {
            return d.longValue();
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
