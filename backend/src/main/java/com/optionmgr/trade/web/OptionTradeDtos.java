package com.optionmgr.trade.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.optionmgr.common.time.MarketLocalDateTimeDeserializer;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionDirection;
import com.optionmgr.trade.domain.Symbols;
import com.optionmgr.trade.domain.TradeAction;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
            /* null only for an expiry (EXP) that released no frozen cash (FEAT-012) */
            Long ledgerEntryId,
            Instant createdAt,
            List<CoveredLotResponse> coveredLots,
            BigDecimal realizedPnl,
            /* stored flag of the trade: set when it is edited (it then has an audit record); create = false */
            boolean hasAuditLog,
            /* FEAT-012: side of the expired position for EXP, null otherwise */
            PositionDirection expiredDirection,
            /* FEAT-013: figures of an assignment (ASG), null otherwise */
            AssignmentResponse assignment,
            /* FEAT-014: figures of an exercise (EXR), null otherwise */
            ExerciseResponse exercise) {
    }

    /**
     * FEAT-014 figures of an exercise under its current match; {@code premium} is positive. Call-only fields
     * (purchaseAmount, stockCost, stockCostPerShare) are null for a put; put-only fields (saleAmount … soldLots) are
     * null for a call.
     */
    public record ExerciseResponse(LocalDate exerciseDate, BigDecimal exerciseFee, BigDecimal premium, int shares,
                                   BigDecimal purchaseAmount, BigDecimal stockCost, BigDecimal stockCostPerShare,
                                   BigDecimal saleAmount, BigDecimal proceeds, BigDecimal soldCost,
                                   BigDecimal stockRealizedPnl,
                                   List<com.optionmgr.stock.web.StockTradeDtos.SoldLotResponse> soldLots,
                                   Long stockTradeId) {
    }

    /** FEAT-014 record an exercise of a long call / put. {@code exerciseFee}: null = 0.00. Other properties ignored. */
    public record ExerciseRequest(
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

            @NotNull(message = "张数不能为空")
            @Positive(message = "张数必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "张数必须是正整数")
            @DecimalMax(value = "100000", message = "张数过大")
            BigDecimal contracts,

            @NotNull(message = "行权日期不能为空")
            LocalDate exerciseDate,

            @PositiveOrZero(message = "行权费不能为负数")
            @Digits(integer = 100, fraction = 2, message = "行权费最多 2 位小数")
            @DecimalMax(value = "9999999999999.99", message = "行权费过大")
            BigDecimal exerciseFee,

            @Size(max = 500, message = "备注最多 500 个字符")
            String note) {

        public ExerciseRequest {
            symbol = Symbols.normalize(symbol);
        }
    }

    /**
     * FEAT-013 / FEAT-041 figures of an assignment under its current match. Put-only fields (purchaseAmount, stockCost,
     * stockCostPerShare) are null for a call; call-only fields (saleAmount … soldLots) are null for a put.
     */
    public record AssignmentResponse(LocalDate assignmentDate, BigDecimal assignmentFee, BigDecimal premium, int shares,
                                     BigDecimal purchaseAmount, BigDecimal stockCost, BigDecimal stockCostPerShare,
                                     BigDecimal saleAmount, BigDecimal proceeds, BigDecimal soldCost,
                                     BigDecimal stockRealizedPnl,
                                     List<com.optionmgr.stock.web.StockTradeDtos.SoldLotResponse> soldLots,
                                     Long stockTradeId) {
    }

    /**
     * FEAT-013 record a short-put assignment. {@code assignmentFee}: null = 0.00. Other properties are ignored.
     */
    public record AssignRequest(
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

            @NotNull(message = "张数不能为空")
            @Positive(message = "张数必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "张数必须是正整数")
            @DecimalMax(value = "100000", message = "张数过大")
            BigDecimal contracts,

            @NotNull(message = "指派日期不能为空")
            LocalDate assignmentDate,

            @PositiveOrZero(message = "指派费不能为负数")
            @Digits(integer = 100, fraction = 2, message = "指派费最多 2 位小数")
            @DecimalMax(value = "9999999999999.99", message = "指派费过大")
            BigDecimal assignmentFee,

            @Size(max = 500, message = "备注最多 500 个字符")
            String note) {

        public AssignRequest {
            symbol = Symbols.normalize(symbol);
        }
    }

    /**
     * FEAT-012 expire a position. {@code contracts} is the expiry size the user saw (0 allowed so that a position
     * without contracts at the cutoff reaches the business checks); every other property is ignored.
     */
    public record ExpireRequest(
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

            @NotNull(message = "张数不能为空")
            @PositiveOrZero(message = "张数必须是非负整数")
            @Digits(integer = 100, fraction = 0, message = "张数必须是非负整数")
            @DecimalMax(value = "100000", message = "张数过大")
            BigDecimal contracts) {

        public ExpireRequest {
            symbol = Symbols.normalize(symbol);
        }
    }

    /** FEAT-043: adjust a contract's expiration date off an ad-hoc closure; other properties are ignored. */
    public record AdjustExpirationRequest(
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

            @NotNull(message = "新到期日不能为空")
            LocalDate newExpirationDate,

            @Size(max = 500, message = "备注最多 500 个字符")
            String note) {

        public AdjustExpirationRequest {
            symbol = Symbols.normalize(symbol);
        }
    }

    /**
     * FEAT-015 batch expiry: the items are validated one by one in the controller (so that duplicate contracts and
     * field errors are reported together, with {@code items[i].field} names); other properties are ignored.
     */
    public record BatchExpireRequest(List<ExpireRequest> items) {
    }

    /** FEAT-015 batch expiry result: expiries in processing order and their totals. */
    public record BatchExpireResponse(int count, BigDecimal realizedPnlTotal, BigDecimal frozenChangeTotal,
                                      List<TradeResponse> expirations) {
    }

    /** FEAT-015: one failed item of a rejected batch (an extension of the common error body). */
    public record BatchExpireFailure(int index, String symbol, LocalDate expirationDate, String strike,
                                     OptionType optionType, String contractLabel, String code, String message) {
    }

    /** FEAT-015: the common error body plus {@code failures} (only for BATCH_EXPIRATION_REJECTED). */
    public record BatchExpireErrorResponse(String code, String message, int status, String path, Instant timestamp,
                                           List<com.optionmgr.common.error.ErrorResponse.FieldError> details,
                                           List<BatchExpireFailure> failures) {
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
