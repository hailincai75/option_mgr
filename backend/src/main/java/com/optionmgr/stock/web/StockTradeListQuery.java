package com.optionmgr.stock.web;

import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse.FieldError;
import com.optionmgr.stock.domain.StockTradeFilter;
import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.trade.domain.Symbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Parses and validates the FEAT-011 stock trade list query (same conventions as FEAT-002): blank = not filtered;
 * unparsable values are reported first as BAD_REQUEST, otherwise rule violations as VALIDATION_ERROR, each with the
 * query parameter name as field.
 */
final class StockTradeListQuery {

    static final int MAX_SIZE = 100;
    static final String TYPE_MESSAGE = "类型只能是 BUY、SELL、TRANSFER_IN、ASSIGN_BUY、ASSIGN_SELL、EXERCISE_BUY、EXERCISE_SELL";
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final List<FieldError> unparsable = new ArrayList<>();
    private final List<FieldError> invalid = new ArrayList<>();

    private StockTradeListQuery() {
    }

    /** @throws ApiException BAD_REQUEST or VALIDATION_ERROR */
    static StockTradeFilter parse(String symbol, List<String> types, String startDate, String endDate, int page,
                                  int size) {
        StockTradeListQuery q = new StockTradeListQuery();
        String sym = q.symbol(symbol);
        Set<StockTradeType> ts = q.types(types);
        LocalDate start = q.date("startDate", startDate);
        LocalDate end = q.date("endDate", endDate);
        if (start != null && end != null && start.isAfter(end)) {
            q.invalid.add(new FieldError("endDate", "开始日期不能晚于结束日期"));
        }
        if (page < 1) {
            q.invalid.add(new FieldError("page", "page 必须 ≥ 1"));
        }
        if (size < 1 || size > MAX_SIZE) {
            q.invalid.add(new FieldError("size", "size 必须在 1 到 100 之间"));
        }
        if (!q.unparsable.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid parameter value", sorted(q.unparsable));
        }
        if (!q.invalid.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed", sorted(q.invalid));
        }
        return new StockTradeFilter(sym, ts, start, end);
    }

    private static List<FieldError> sorted(List<FieldError> errors) {
        return errors.stream().distinct()
                .sorted((a, b) -> a.field().equals(b.field())
                        ? a.message().compareTo(b.message()) : a.field().compareTo(b.field()))
                .toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private String symbol(String raw) {
        if (blank(raw)) {
            return null;
        }
        String s = Symbols.normalize(raw);
        if (!s.matches(Symbols.NORMALIZED_PATTERN)) {
            invalid.add(new FieldError("symbol", "标的只能包含字母、数字和点"));
        }
        if (s.length() > Symbols.MAX_LENGTH) {
            invalid.add(new FieldError("symbol", "标的最多 10 个字符"));
        }
        return s;
    }

    private LocalDate date(String field, String raw) {
        if (blank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip(), DATE);
        } catch (DateTimeParseException e) {
            unparsable.add(new FieldError(field, "日期格式不正确（YYYY-MM-DD）"));
            return null;
        }
    }

    private Set<StockTradeType> types(List<String> raw) {
        Set<StockTradeType> result = EnumSet.noneOf(StockTradeType.class);
        if (raw == null) {
            return result;
        }
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    result.add(StockTradeType.valueOf(part.strip()));
                } catch (IllegalArgumentException e) {
                    unparsable.add(new FieldError("type", TYPE_MESSAGE));
                }
            }
        }
        return result;
    }
}
