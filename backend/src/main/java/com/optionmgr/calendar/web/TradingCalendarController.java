package com.optionmgr.calendar.web;

import com.optionmgr.calendar.domain.AdhocClosure;
import com.optionmgr.calendar.domain.AdhocClosureStatus;
import com.optionmgr.calendar.domain.FetchResult;
import com.optionmgr.calendar.domain.FetchStatus;
import com.optionmgr.calendar.domain.HolidaySource;
import com.optionmgr.calendar.domain.NonTradingDay;
import com.optionmgr.calendar.domain.NonTradingDayType;
import com.optionmgr.calendar.service.AdhocClosureService;
import com.optionmgr.calendar.service.TradingCalendarService;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.common.time.MarketTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-009: the US trading calendar. One for all accounts (Q53): no X-Account-Id. */
@RestController
@RequestMapping("/api/trading-calendar")
public class TradingCalendarController {

    /** Maximum {@code to - from} in days. */
    public static final long MAX_RANGE_DAYS = 366;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final TradingCalendarService calendar;
    private final AdhocClosureService adhocClosures;

    public TradingCalendarController(TradingCalendarService calendar, AdhocClosureService adhocClosures) {
        this.calendar = calendar;
        this.adhocClosures = adhocClosures;
    }

    /** FEAT-042: all detected ad-hoc closures (REVOKED included). */
    public record AdhocClosuresResponse(List<AdhocClosureResponse> closures) {
    }

    public record AdhocClosureResponse(LocalDate date, String eventName, LocalDate detectedOn,
                                       AdhocClosureStatus status, LocalDate revokedOn) {
        static AdhocClosureResponse of(AdhocClosure c) {
            return new AdhocClosureResponse(c.date(), c.eventName(), c.detectedOn(), c.status(), c.revokedOn());
        }
    }

    @GetMapping("/adhoc-closures")
    public AdhocClosuresResponse adhocClosures(@RequestParam(required = false) String status) {
        Optional<AdhocClosureStatus> filter = Optional.empty();
        if (status != null) {
            try {
                filter = Optional.of(AdhocClosureStatus.valueOf(status));
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid parameter value",
                        List.of(new ErrorResponse.FieldError("status", "must be one of ACTIVE, REVOKED")));
            }
        }
        return new AdhocClosuresResponse(adhocClosures.list(filter).stream().map(AdhocClosureResponse::of).toList());
    }

    public record NonTradingDaysResponse(LocalDate from, LocalDate to, List<DayResponse> days) {
    }

    public record DayResponse(LocalDate date, NonTradingDayType type, String name, HolidaySource source) {
        static DayResponse of(NonTradingDay d) {
            return new DayResponse(d.date(), d.type(), d.name(), d.source());
        }
    }

    public record StatusResponse(boolean stale, String warning, Instant lastAttemptAt, FetchResult lastAttemptResult,
                                 String lastFailureDetail, Instant lastSuccessAt, String lastSuccessAtLocal,
                                 List<Integer> coveredYears) {
    }

    @GetMapping("/non-trading-days")
    public NonTradingDaysResponse nonTradingDays(@RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        List<ErrorResponse.FieldError> bad = new ArrayList<>();
        LocalDate f = parse("from", from, bad);
        LocalDate t = parse("to", to, bad);
        if (!bad.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid parameter value", bad);
        }
        if (f.isAfter(t)) {
            throw invalidTo("开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(f, t) > MAX_RANGE_DAYS) {
            throw invalidTo("日期范围最多 366 天");
        }
        List<DayResponse> days = calendar.calendar().nonTradingDays(f, t).stream().map(DayResponse::of).toList();
        return new NonTradingDaysResponse(f, t, days);
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return toResponse(calendar.status(), calendar.calendar().coveredYears());
    }

    public static StatusResponse toResponse(FetchStatus s, List<Integer> coveredYears) {
        return new StatusResponse(s.stale(), s.warning(), s.lastAttemptAt(), s.lastAttemptResult(),
                s.lastFailureDetail(), s.lastSuccessAt(),
                s.lastSuccessAt() == null ? null : LOCAL_SECONDS.format(MarketTime.toLocal(s.lastSuccessAt())),
                coveredYears);
    }

    private static LocalDate parse(String field, String text, List<ErrorResponse.FieldError> bad) {
        if (text == null || text.isBlank()) {
            bad.add(new ErrorResponse.FieldError(field, "请填写日期"));
            return null;
        }
        try {
            return LocalDate.parse(text.strip(), DATE);
        } catch (DateTimeParseException e) {
            bad.add(new ErrorResponse.FieldError(field, "日期格式不正确（YYYY-MM-DD）"));
            return null;
        }
    }

    private static ApiException invalidTo(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                List.of(new ErrorResponse.FieldError("to", message)));
    }
}
