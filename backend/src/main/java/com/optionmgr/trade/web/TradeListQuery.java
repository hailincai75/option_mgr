package com.optionmgr.trade.web;

import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse.FieldError;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.Symbols;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeFilter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Parses and validates the FEAT-002 list query parameters (see docs/api/api-contract.md). Blank = not filtered.
 * Unparsable values are reported first as BAD_REQUEST; otherwise rule violations as VALIDATION_ERROR, each with
 * the query parameter name as field.
 */
final class TradeListQuery {

    static final int MAX_SIZE = 100;
    private static final BigDecimal MAX_STRIKE = new BigDecimal("9999999.999");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final List<FieldError> unparsable = new ArrayList<>();
    private final List<FieldError> invalid = new ArrayList<>();

    private static final String ALL_ACTIONS_MESSAGE = "方向只能是 BTO、STO、BTC、STC";

    private final Set<TradeAction> allowedActions;
    private final String actionMessage;

    private TradeListQuery(Set<TradeAction> allowedActions, String actionMessage) {
        this.allowedActions = allowedActions;
        this.actionMessage = actionMessage;
    }

    /** @throws ApiException BAD_REQUEST or VALIDATION_ERROR */
    static TradeFilter parse(String symbol, String expirationDate, String strike, String optionType,
                             List<String> actions, String startDate, String endDate, int page, int size) {
        return parse(symbol, expirationDate, strike, optionType, actions, startDate, endDate, page, size,
                EnumSet.allOf(TradeAction.class), ALL_ACTIONS_MESSAGE);
    }

    /**
     * As above, accepting only {@code allowedActions} in {@code action} (others: BAD_REQUEST with
     * {@code actionMessage}); FEAT-006 uses BTC / STC.
     */
    static TradeFilter parse(String symbol, String expirationDate, String strike, String optionType,
                             List<String> actions, String startDate, String endDate, int page, int size,
                             Set<TradeAction> allowedActions, String actionMessage) {
        TradeListQuery q = new TradeListQuery(allowedActions, actionMessage);
        String sym = q.symbol(symbol);
        LocalDate exp = q.date("expirationDate", expirationDate);
        BigDecimal k = q.strike(strike);
        OptionType type = q.optionType(optionType);
        Set<TradeAction> acts = q.actions(actions);
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
        return new TradeFilter(sym, exp, k, type, acts, start, end);
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

    private BigDecimal strike(String raw) {
        if (blank(raw)) {
            return null;
        }
        BigDecimal k;
        try {
            k = new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            unparsable.add(new FieldError("strike", "行权价必须是数字"));
            return null;
        }
        boolean ok = true;
        if (k.signum() <= 0) {
            invalid.add(new FieldError("strike", "行权价必须大于 0"));
            ok = false;
        }
        if (k.stripTrailingZeros().scale() > 3) {
            invalid.add(new FieldError("strike", "行权价最多 3 位小数"));
            ok = false;
        }
        if (k.compareTo(MAX_STRIKE) > 0) {
            invalid.add(new FieldError("strike", "行权价过大"));
            ok = false;
        }
        return ok ? k : null;
    }

    private OptionType optionType(String raw) {
        if (blank(raw)) {
            return null;
        }
        try {
            return OptionType.valueOf(raw.strip());
        } catch (IllegalArgumentException e) {
            unparsable.add(new FieldError("optionType", "类型只能是 CALL 或 PUT"));
            return null;
        }
    }

    private Set<TradeAction> actions(List<String> raw) {
        Set<TradeAction> result = EnumSet.noneOf(TradeAction.class);
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
                TradeAction a = null;
                try {
                    a = TradeAction.valueOf(part.strip());
                } catch (IllegalArgumentException e) {
                    // reported below
                }
                if (a == null || !allowedActions.contains(a)) {
                    unparsable.add(new FieldError("action", actionMessage));
                } else {
                    result.add(a);
                }
            }
        }
        return result;
    }
}
