package com.optionmgr.testsupport.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
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
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.quote.web.MarketDataController;
import com.optionmgr.testsupport.E2eProfile;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    public MarketDataTestSupportController(ObjectProvider<InMemoryQuoteTransport> transport, QuoteFetchService fetches,
                                           QuoteQueryService queries, UnderlyingQuoteRepository underlyings,
                                           OptionQuoteRepository options, UnderlyingQuoteCloseRepository underlyingCloses,
                                           OptionQuoteCloseRepository optionCloses, ObjectMapper mapper) {
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
                                 Map<String, ReplyRequest> finnhub) {
    }

    public record RequestsResponse(List<InMemoryQuoteTransport.Request> requests) {
    }

    public record TickResponse(boolean ran, RunTrigger trigger, String skipReason,
                               List<MarketDataController.SymbolResultResponse> results, QuoteViews.Status status) {
    }

    public record StoredUnderlying(String symbol, String price, String bid, String ask, String prevClose, String source,
                                   Instant quoteTime, Instant lastPriceSuccessAt, Instant lastChainSuccessAt,
                                   Instant lastAttemptAt, String lastCboeResult, String lastFinnhubResult,
                                   String manualPrice, Instant manualEnteredAt) {
    }

    public record StoredContract(String symbol, LocalDate expirationDate, String strike, OptionType optionType,
                                 String occCode, String bid, String ask, String last, String theo, String iv,
                                 String delta, String gamma, String theta, String vega, String rho, Long volume,
                                 Long openInterest, Instant quoteTime, Instant fetchedAt, Instant missingSince,
                                 String manualMark, Instant manualEnteredAt) {
    }

    public record CloseUnderlying(String symbol, String price, String bid, String ask, String prevClose, String source,
                                  Instant quoteTime, Instant capturedAt) {
    }

    public record CloseContract(String symbol, LocalDate expirationDate, String strike, OptionType optionType,
                                String occCode, String bid, String ask, String last, String theo, String iv,
                                String delta, String gamma, String theta, String vega, String rho, Long volume,
                                Long openInterest, Instant quoteTime, Instant capturedAt) {
    }

    public record CloseSnapshot(LocalDate date, List<CloseUnderlying> underlyings, List<CloseContract> contracts) {
    }

    public record StoredResponse(List<StoredUnderlying> underlyings, List<StoredContract> contracts,
                                 List<CloseSnapshot> closeSnapshots) {
    }

    @PutMapping("/sources")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sources(@RequestBody SourcesRequest req) {
        InMemoryQuoteTransport t = fake();
        t.configure(new InMemoryQuoteTransport.Sources(req.finnhubKeyPresent() == null || req.finnhubKeyPresent(),
                replies("cboe", req.cboe()), replies("finnhub", req.finnhub())));
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
                    str(s.manualPrice()), s.manualEnteredAt());
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
                    e.getVolume(), e.getOpenInterest(), e.getQuoteTime(), e.getCapturedAt()));
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
                e.getMissingSince(), str(e.getManualMark()), e.getManualEnteredAt());
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
            ReplyRequest r = e.getValue();
            if (r == null || Boolean.TRUE.equals(r.unreachable())) {
                out.put(e.getKey(), InMemoryQuoteTransport.Reply.unreachableReply());
                continue;
            }
            if (r.httpStatus() == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                        List.of(new ErrorResponse.FieldError(field + "." + e.getKey() + ".httpStatus",
                                "httpStatus 或 unreachable 必填")));
            }
            out.put(e.getKey(), InMemoryQuoteTransport.Reply.of(r.httpStatus(),
                    r.rawBody() != null ? r.rawBody() : bodyText(r.body())));
        }
        return out;
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
