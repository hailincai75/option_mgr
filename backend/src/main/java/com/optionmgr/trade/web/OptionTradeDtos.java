package com.optionmgr.trade.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.optionmgr.common.time.MarketLocalDateTimeDeserializer;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.Symbols;
import com.optionmgr.trade.domain.TradeAction;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Request/response bodies of the FEAT-001 option trade API (see docs/api/api-contract.md). */
public final class OptionTradeDtos {

    private OptionTradeDtos() {
    }

    /**
     * {@code contracts} and {@code multiplier} are bound as BigDecimal so that a fractional value such as 1.5 is
     * rejected by validation instead of being silently truncated to an int by Jackson.
     */
    public record OpenTradeRequest(
            @NotEmpty(message = "标的不能为空")
            @Size(max = Symbols.MAX_LENGTH, message = "标的最多 10 个字符")
            @Pattern(regexp = Symbols.NORMALIZED_PATTERN, message = "标的只能包含字母、数字和点")
            String symbol,

            @NotNull(message = "到期日不能为空")
            LocalDate expirationDate,

            @NotNull(message = "行权价不能为空")
            @Positive(message = "行权价必须大于 0")
            @Digits(integer = 100, fraction = 3, message = "行权价最多 3 位小数")
            @DecimalMax(value = "9999999.999", message = "行权价过大")
            BigDecimal strike,

            @NotNull(message = "请选择 Call 或 Put")
            OptionType optionType,

            @NotNull(message = "请选择方向")
            @OpeningAction
            TradeAction action,

            @NotNull(message = "张数不能为空")
            @Positive(message = "张数必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "张数必须是正整数")
            @DecimalMax(value = "100000", message = "张数过大")
            BigDecimal contracts,

            @NotNull(message = "净额不能为空")
            @Positive(message = "净额必须大于 0")
            @Digits(integer = 100, fraction = 2, message = "净额最多 2 位小数")
            @DecimalMax(value = "9999999999999.99", message = "净额过大")
            BigDecimal netAmount,

            @NotNull(message = "成交时间不能为空")
            @JsonDeserialize(using = MarketLocalDateTimeDeserializer.class)
            LocalDateTime tradedAtLocal,

            @Positive(message = "multiplier 必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "multiplier 必须是正整数")
            @DecimalMax(value = "10000", message = "multiplier 过大")
            BigDecimal multiplier,

            @Size(max = 500, message = "备注最多 500 个字符")
            String note) {

        public OpenTradeRequest {
            symbol = Symbols.normalize(symbol);
        }
    }

    /** FEAT-002 trade list page, newest first. */
    public record TradePageResponse(List<TradeResponse> items, int page, int size, long totalItems,
                                    int totalPages) {
    }

    public record TradeResponse(
            long id,
            String symbol,
            LocalDate expirationDate,
            String strike,
            OptionType optionType,
            TradeAction action,
            int contracts,
            int multiplier,
            BigDecimal netAmount,
            Instant tradedAt,
            String tradedAtLocal,
            BigDecimal cashChange,
            BigDecimal frozenChange,
            String contractLabel,
            String note,
            long ledgerEntryId,
            Instant createdAt,
            List<CoveredLotResponse> coveredLots,
            BigDecimal realizedPnl,
            /* stored flag of the trade: set when it is edited (it then has an audit record); create = false */
            boolean hasAuditLog) {
    }

    /**
     * FEAT-005 edit (Q43): only editable fields; contract and action are not part of it (extra properties ignored).
     * {@code multiplier}: opens only, null = unchanged; ignored for closes.
     */
    public record EditTradeRequest(
            @NotNull(message = "张数不能为空")
            @Positive(message = "张数必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "张数必须是正整数")
            @DecimalMax(value = "100000", message = "张数过大")
            BigDecimal contracts,

            @NotNull(message = "净额不能为空")
            @Positive(message = "净额必须大于 0")
            @Digits(integer = 100, fraction = 2, message = "净额最多 2 位小数")
            @DecimalMax(value = "9999999999999.99", message = "净额过大")
            BigDecimal netAmount,

            @NotNull(message = "成交时间不能为空")
            @JsonDeserialize(using = MarketLocalDateTimeDeserializer.class)
            LocalDateTime tradedAtLocal,

            @Positive(message = "multiplier 必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "multiplier 必须是正整数")
            @DecimalMax(value = "10000", message = "multiplier 过大")
            BigDecimal multiplier,

            @Size(max = 500, message = "备注最多 500 个字符")
            String note) {
    }

    /** FEAT-004: one whole lot covered by a close. */
    public record CoveredLotResponse(long tradeId, TradeAction action, Instant tradedAt, String tradedAtLocal,
                                     int contracts, BigDecimal netAmount, BigDecimal frozen) {
    }

    /** FEAT-004 closing trade; no multiplier (taken from the position; an extra "multiplier" property is ignored). */
    public record CloseTradeRequest(
            @NotEmpty(message = "标的不能为空")
            @Size(max = Symbols.MAX_LENGTH, message = "标的最多 10 个字符")
            @Pattern(regexp = Symbols.NORMALIZED_PATTERN, message = "标的只能包含字母、数字和点")
            String symbol,

            @NotNull(message = "到期日不能为空")
            LocalDate expirationDate,

            @NotNull(message = "行权价不能为空")
            @Positive(message = "行权价必须大于 0")
            @Digits(integer = 100, fraction = 3, message = "行权价最多 3 位小数")
            @DecimalMax(value = "9999999.999", message = "行权价过大")
            BigDecimal strike,

            @NotNull(message = "请选择 Call 或 Put")
            OptionType optionType,

            @NotNull(message = "请选择方向")
            @ClosingAction
            TradeAction action,

            @NotNull(message = "张数不能为空")
            @Positive(message = "张数必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "张数必须是正整数")
            @DecimalMax(value = "100000", message = "张数过大")
            BigDecimal contracts,

            @NotNull(message = "净额不能为空")
            @Positive(message = "净额必须大于 0")
            @Digits(integer = 100, fraction = 2, message = "净额最多 2 位小数")
            @DecimalMax(value = "9999999999999.99", message = "净额过大")
            BigDecimal netAmount,

            @NotNull(message = "成交时间不能为空")
            @JsonDeserialize(using = MarketLocalDateTimeDeserializer.class)
            LocalDateTime tradedAtLocal,

            @Size(max = 500, message = "备注最多 500 个字符")
            String note) {

        public CloseTradeRequest {
            symbol = Symbols.normalize(symbol);
        }
    }
}
