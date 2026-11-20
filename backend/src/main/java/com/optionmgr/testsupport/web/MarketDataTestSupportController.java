package com.optionmgr.testsupport.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.quote.domain.QuotePrices;
import com.optionmgr.quote.domain.QuoteSource;
import com.optionmgr.quote.domain.RunTrigger;
import com.optionmgr.quote.repository.OptionQuoteCloseEntity;
import com.optionmgr.quote.repository.OptionQuoteCloseRepository;
import com.optionmgr.quote.repository.OptionQuoteColumns;
import com.optionmgr.quote.repository.OptionQuoteEntity;
import com.optionmgr.quote.repository.OptionQuoteRepository;
import com.optionmgr.quote.repository.UnderlyingQuoteCloseEntity;
import com.optionmgr.quote.repository.UnderlyingQuoteCloseRepository;
import com.optionmgr.quote.repository.UnderlyingQuoteRepository;
import com.optionmgr.quote.domain.UnderlyingState;
import com.optionmgr.quote.service.InMemoryQuoteTransport;
import com.optionmgr.quote.service.QuoteFetchService;
import com.optionmgr.quote.service.QuoteProperties;
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteSettings;
import com.optionmgr.quote.service.YahooSession;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.quote.web.MarketDataController;
import com.optionmgr.testsupport.E2eProfile;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * FEAT-016 test support (e2e profile only): fake CBOE / Finnhub replies, the request log, a simulated scheduler tick
 * and a dump of the stored quotes. See docs/api/FEAT-016.md「测试支持」.
 */
@RestController
@Profile(E2eProfile.NAME)
@RequestMapping("/api/test-support/market-data")
public class MarketDataTestSupportController {

    private final ObjectProvider<InMemoryQuoteTransport> transport;
    private final QuoteFetchService fetches;
    private final QuoteQueryService queries;
    private final UnderlyingQuoteRepository underlyings;
    private final OptionQuoteRepository options;
    private final UnderlyingQuoteCloseRepository underlyingCloses;
    private final OptionQuoteCloseRepository optionCloses;
    private final ObjectMapper mapper;

    private final QuoteSettings settings;
    private final YahooSession session;

    public MarketDataTestSupportController(ObjectProvider<InMemoryQuoteTransport> transport, QuoteFetchService fetches,
                                           QuoteQueryService queries, UnderlyingQuoteRepository underlyings,
                                           OptionQuoteRepository options, UnderlyingQuoteCloseRepository underlyingCloses,
                                           OptionQuoteCloseRepository optionCloses, ObjectMapper mapper,
                                           QuoteSettings settings, YahooSession session) {
        this.settings = settings;
        this.session = session;
        this.transport = transport;
        this.fetches = fetches;
        this.queries = queries;
        this.underlyings = underlyings;
        this.options = options;
        this.underlyingCloses = underlyingCloses;
        this.optionCloses = optionCloses;
        this.mapper = mapper;
    }

    /** One configured reply: {httpStatus, body} / {httpStatus, rawBody} / {unreachable: true}. */
    public record ReplyRequest(Integer httpStatus, JsonNode body, String rawBody, Boolean unreachable) {
    }

    public record SourcesRequest(Boolean finnhubKeyPresent, Map<String, ReplyRequest> cboe,
                                 Map<String, ReplyRequest> finnhub, YahooRequest yahoo) {
    }

    public record RequestsResponse(List<InMemoryQuoteTransport.Request> requests) {
    }

    public record TickResponse(boolean ran, RunTrigger trigger, String skipReason,
                               List<MarketDataController.SymbolResultResponse> results, QuoteViews.Status status) {
    }

    public record StoredUnderlying(String symbol, String price, String bid, String ask, String prevClose, String source,
                                   Instant quoteTime, Instant lastPriceSuccessAt, Instant lastChainSuccessAt,
                                   Instant lastAttemptAt, String lastCboeResult, String lastFinnhubResult,
                                   String manualPrice, Instant manualEnteredAt, String lastYahooResult) {
    }

    public record StoredContract(String symbol, LocalDate expirationDate, String strike, OptionType optionType,
                                 String occCode, String bid, String ask, String last, String theo, String iv,
                                 String delta, String gamma, String theta, String vega, String rho, Long volume,
                                 Long openInterest, Instant quoteTime, Instant fetchedAt, Instant missingSince,
                                 String manualMark, Instant manualEnteredAt, String source) {
    }

    public record CloseUnderlying(String symbol, String price, String bid, String ask, String prevClose, String source,
                                  Instant quoteTime, Instant capturedAt) {
    }

    public record CloseContract(String symbol, LocalDate expirationDate, String strike, OptionType optionType,
                                String occCode, String bid, String ask, String last, String theo, String iv,
                                String delta, String gamma, String theta, String vega, String rho, Long volume,
                                Long openInterest, Instant quoteTime, Instant capturedAt, String source) {
    }

    public record CloseSnapshot(LocalDate date, List<CloseUnderlying> underlyings, List<CloseContract> contracts) {
    }

    public record StoredResponse(List<StoredUnderlying> underlyings, List<StoredContract> contracts,
                                 List<CloseSnapshot> closeSnapshots) {
    }

    /** FEAT-052: fake Yahoo; each reply value is one reply object or an array of them (a sequence). */
    public record YahooRequest(JsonNode handshake, Map<String, YahooSymbolRequest> symbols) {
    }

    public record YahooSymbolRequest(Map<String, JsonNode> byDate, @JsonProperty("default") JsonNode fallback) {
    }

    /** FEAT-052: null = the configured value. A non-numeric rate cannot be bound (400 BAD_REQUEST). */
    public record SettingsRequest(String optionSource, BigDecimal riskFreeRatePercent) {
    }

    public record SettingsResponse(QuoteSource optionSource, BigDecimal riskFreeRatePercent) {
    }

    @PutMapping("/sources")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sources(@RequestBody SourcesRequest req) {
        InMemoryQuoteTransport t = fake();
        t.configure(new InMemoryQuoteTransport.Sources(req.finnhubKeyPresent() == null || req.finnhubKeyPresent(),
                replies("cboe", req.cboe()), replies("finnhub", req.finnhub()), yahoo(req.yahoo())));
        // FEAT-052: the next run handshakes again
        session.clear();
    }

    /** FEAT-052: the option source and risk-free rate, as if configured and restarted. */
    @PutMapping("/settings")
    public SettingsResponse settings(@RequestBody(required = false) SettingsRequest req) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        QuoteSource source = null;
        BigDecimal rate = null;
        if (req != null && req.optionSource() != null) {
            try {
                source = QuoteSource.parse(req.optionSource());
            } catch (IllegalArgumentException e) {
                errors.add(new ErrorResponse.FieldError("optionSource", "数据源只能是 YAHOO 或 CBOE"));
            }
        }
        if (req != null && req.riskFreeRatePercent() != null) {
            try {
                rate = req.riskFreeRatePercent();
                QuoteProperties.checkRate(rate);
            } catch (IllegalArgumentException e) {
                rate = null;
                errors.add(new ErrorResponse.FieldError("riskFreeRatePercent", "无风险利率须为 0 到 20 之间的数字"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed", errors);
        }
        QuoteSettings.Values v = settings.set(source, rate);
        return new SettingsResponse(v.optionSource(), QuotePrices.display(v.riskFreeRatePercent()));
    }

    @GetMapping("/requests")
    public RequestsResponse requests() {
        return new RequestsResponse(fake().requests());
    }

    @DeleteMapping("/requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearRequests() {
        fake().clearRequests();
    }

    @PostMapping("/tick")
    public TickResponse tick() {
        QuoteFetchService.TickOutcome t = fetches.tick();
        return new TickResponse(t.ran(), t.trigger(), t.skipReason(), MarketDataController.results(t.results()),
                queries.status());
    }

    @GetMapping("/stored")
    @Transactional(readOnly = true)
    public StoredResponse stored() {
        List<StoredUnderlying> us = underlyings.findAll().stream().map(e -> {
            UnderlyingState s = e.toDomain();
            return new StoredUnderlying(s.symbol(), str(s.price()), str(s.bid()), str(s.ask()), str(s.prevClose()),
                    s.source() == null ? null : s.source().name(), s.quoteTime(), s.lastPriceSuccessAt(),
                    s.lastChainSuccessAt(), s.lastAttemptAt(), name(s.lastCboeResult()), name(s.lastFinnhubResult()),
                    str(s.manualPrice()), s.manualEnteredAt(), name(s.lastYahooResult()));
        }).sorted(Comparator.comparing(StoredUnderlying::symbol)).toList();
        List<StoredContract> cs = options.findAll().stream().sorted(byContract())
                .map(MarketDataTestSupportController::stored).toList();
        Map<LocalDate, List<CloseUnderlying>> cu = new TreeMap<>();
        for (UnderlyingQuoteCloseEntity e : underlyingCloses.findAll()) {
            cu.computeIfAbsent(e.getSnapshotDate(), d -> new ArrayList<>()).add(new CloseUnderlying(e.getSymbol(),
                    str(e.getPrice()), str(e.getBid()), str(e.getAsk()), str(e.getPrevClose()), e.getPriceSource(),
                    e.getQuoteTime(), e.getCapturedAt()));
        }
        Map<LocalDate, List<CloseContract>> cc = new HashMap<>();
        for (OptionQuoteCloseEntity e : optionCloses.findAll().stream().sorted(byContract()).toList()) {
            ContractKey k = e.contract();
            cc.computeIfAbsent(e.getSnapshotDate(), d -> new ArrayList<>()).add(new CloseContract(k.symbol(),
                    k.expirationDate(), ContractKey.strikeText(k.strike()), k.optionType(), e.getOccCode(),
                    str(e.getBid()), str(e.getAsk()), str(e.getLastPrice()), str(e.getTheo()), str(e.getIv()),
                    str(e.getDelta()), str(e.getGamma()), str(e.getTheta()), str(e.getVega()), str(e.getRho()),
                    e.getVolume(), e.getOpenInterest(), e.getQuoteTime(), e.getCapturedAt(), e.getQuoteSource()));
        }
        TreeSet<LocalDate> dates = new TreeSet<>(cu.keySet());
        dates.addAll(cc.keySet());
        List<CloseSnapshot> snaps = dates.stream().map(d -> new CloseSnapshot(d,
                cu.getOrDefault(d, List.of()).stream().sorted(Comparator.comparing(CloseUnderlying::symbol)).toList(),
                cc.getOrDefault(d, List.of()))).toList();
        return new StoredResponse(us, cs, snaps);
    }

    private static StoredContract stored(OptionQuoteEntity e) {
        ContractKey k = e.contract();
        return new StoredContract(k.symbol(), k.expirationDate(), ContractKey.strikeText(k.strike()), k.optionType(),
                e.getOccCode(), str(e.getBid()), str(e.getAsk()), str(e.getLastPrice()), str(e.getTheo()),
                str(e.getIv()), str(e.getDelta()), str(e.getGamma()), str(e.getTheta()), str(e.getVega()),
                str(e.getRho()), e.getVolume(), e.getOpenInterest(), e.getQuoteTime(), e.getFetchedAt(),
                e.getMissingSince(), str(e.getManualMark()), e.getManualEnteredAt(), e.getQuoteSource());
    }

    private static <T extends OptionQuoteColumns> Comparator<T> byContract() {
        return Comparator.comparing((T e) -> e.contract().symbol())
                .thenComparing(e -> e.contract().expirationDate())
                .thenComparing(e -> e.contract().strike())
                .thenComparing(e -> e.contract().optionType());
    }

    private InMemoryQuoteTransport fake() {
        InMemoryQuoteTransport t = transport.getIfAvailable();
        if (t == null) {
            throw ApiException.notFound("Resource not found");
        }
        return t;
    }

    private Map<String, InMemoryQuoteTransport.Reply> replies(String field, Map<String, ReplyRequest> in) {
        Map<String, InMemoryQuoteTransport.Reply> out = new HashMap<>();
        if (in == null) {
            return out;
        }
        for (Map.Entry<String, ReplyRequest> e : in.entrySet()) {
            out.put(e.getKey(), reply(field + "." + e.getKey(), e.getValue()));
        }
        return out;
    }

    private InMemoryQuoteTransport.Reply reply(String field, ReplyRequest r) {
        if (r == null || Boolean.TRUE.equals(r.unreachable())) {
            return InMemoryQuoteTransport.Reply.unreachableReply();
        }
        if (r.httpStatus() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                    List.of(new ErrorResponse.FieldError(field + ".httpStatus", "httpStatus 或 unreachable 必填")));
        }
        return InMemoryQuoteTransport.Reply.of(r.httpStatus(), r.rawBody() != null ? r.rawBody() : bodyText(r.body()));
    }

    /** FEAT-052: one reply object or an array of them; null / missing = not configured. */
    private List<InMemoryQuoteTransport.Reply> sequence(String field, JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        List<JsonNode> items = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(items::add);
        } else {
            items.add(node);
        }
        List<InMemoryQuoteTransport.Reply> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String f = node.isArray() ? field + "[" + i + "]" : field;
            if (!items.get(i).isObject()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                        List.of(new ErrorResponse.FieldError(f, "回复必须是对象或对象数组")));
            }
            ReplyRequest r;
            try {
                r = mapper.treeToValue(items.get(i), ReplyRequest.class);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                        List.of(new ErrorResponse.FieldError(f, "回复格式不正确")));
            }
            out.add(reply(f, r));
        }
        return out;
    }

    private InMemoryQuoteTransport.Yahoo yahoo(YahooRequest req) {
        if (req == null) {
            return InMemoryQuoteTransport.Yahoo.EMPTY;
        }
        Map<String, InMemoryQuoteTransport.YahooSymbol> symbols = new HashMap<>();
        if (req.symbols() != null) {
            for (Map.Entry<String, YahooSymbolRequest> e : req.symbols().entrySet()) {
                String field = "yahoo.symbols." + e.getKey();
                YahooSymbolRequest s = e.getValue();
                if (s == null) {
                    continue;
                }
                Map<LocalDate, List<InMemoryQuoteTransport.Reply>> byDate = new HashMap<>();
                if (s.byDate() != null) {
                    for (Map.Entry<String, JsonNode> d : s.byDate().entrySet()) {
                        LocalDate date;
                        try {
                            date = LocalDate.parse(d.getKey());
                        } catch (DateTimeParseException ex) {
                            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                                    List.of(new ErrorResponse.FieldError(field + ".byDate." + d.getKey(),
                                            "日期格式为 YYYY-MM-DD")));
                        }
                        List<InMemoryQuoteTransport.Reply> seq = sequence(field + ".byDate." + d.getKey(),
                                d.getValue());
                        if (seq != null) {
                            byDate.put(date, seq);
                        }
                    }
                }
                symbols.put(e.getKey(), new InMemoryQuoteTransport.YahooSymbol(byDate,
                        sequence(field + ".default", s.fallback())));
            }
        }
        return new InMemoryQuoteTransport.Yahoo(sequence("yahoo.handshake", req.handshake()), symbols);
    }

    private String bodyText(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
