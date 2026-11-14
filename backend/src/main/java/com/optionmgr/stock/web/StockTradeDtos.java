package com.optionmgr.stock.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.optionmgr.common.time.MarketLocalDateTimeDeserializer;
import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.trade.domain.Symbols;
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

/** Request/response bodies of the FEAT-011 stock API (see docs/api/api-contract.md). */
public final class StockTradeDtos {

    private StockTradeDtos() {
    }

    /**
     * New stock record. {@code shares} is bound as BigDecimal so that 1.5 is rejected instead of truncated.
     * {@code tradedAtLocal} (BUY / SELL) or {@code tradeDate} (TRANSFER_IN) is required by type; the other is ignored.
     */
    @StockTradeTimeRequired
    public record CreateStockTradeRequest(
            @NotNull(message = "请选择类型")
            StockTradeType type,

            @NotEmpty(message = "标的不能为空")
            @Size(max = Symbols.MAX_LENGTH, message = "标的最多 10 个字符")
            @Pattern(regexp = Symbols.NORMALIZED_PATTERN, message = "标的只能包含字母、数字和点")
            String symbol,

            @NotNull(message = "股数不能为空")
            @Positive(message = "股数必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "股数必须是正整数")
            @DecimalMax(value = "10000000", message = "股数过大")
            BigDecimal shares,

            @NotNull(message = "金额不能为空")
            @Positive(message = "金额必须大于 0")
            @Digits(integer = 100, fraction = 2, message = "金额最多 2 位小数")
            @DecimalMax(value = "9999999999999.99", message = "金额过大")
            BigDecimal amount,

            @JsonDeserialize(using = MarketLocalDateTimeDeserializer.class)
            LocalDateTime tradedAtLocal,

            LocalDate tradeDate,

            @Size(max = 500, message = "备注最多 500 个字符")
            String note) {

        public CreateStockTradeRequest {
            symbol = Symbols.normalize(symbol);
        }
    }

    /**
     * Edit (whole replacement of the editable fields). The time field is required by the stored type (checked by the
     * service). {@code symbol} / {@code type} are optional; when given they must equal the stored values.
     */
    public record EditStockTradeRequest(
            @NotNull(message = "股数不能为空")
            @Positive(message = "股数必须是正整数")
            @Digits(integer = 100, fraction = 0, message = "股数必须是正整数")
            @DecimalMax(value = "10000000", message = "股数过大")
            BigDecimal shares,

            @NotNull(message = "金额不能为空")
            @Positive(message = "金额必须大于 0")
            @Digits(integer = 100, fraction = 2, message = "金额最多 2 位小数")
            @DecimalMax(value = "9999999999999.99", message = "金额过大")
            BigDecimal amount,

            @JsonDeserialize(using = MarketLocalDateTimeDeserializer.class)
            LocalDateTime tradedAtLocal,

            LocalDate tradeDate,

            @Size(max = 500, message = "备注最多 500 个字符")
            String note,

            String symbol,

            StockTradeType type) {
    }

    /** One lot (part) taken by a sale. */
    public record SoldLotResponse(long tradeId, StockTradeType type, Instant tradedAt, String tradedAtLocal,
                                 LocalDate tradeDate, int shares, BigDecimal cost) {
    }

    public record StockTradeResponse(
            long id,
            StockTradeType type,
            String symbol,
            int shares,
            BigDecimal amount,
            BigDecimal cashChange,
            BigDecimal pricePerShare,
            Instant tradedAt,
            String tradedAtLocal,
            /* TRANSFER_IN only: its New York date */
            LocalDate tradeDate,
            String note,
            /* null for TRANSFER_IN (no cash effect) */
            Long ledgerEntryId,
            Instant createdAt,
            boolean hasAuditLog,
            /* SELL only (null otherwise), from the current FIFO replay */
            BigDecimal soldCost,
            BigDecimal realizedPnl,
            List<SoldLotResponse> soldLots,
            /* FEAT-013: ASSIGN_BUY: the assignment (option trade id) that generated it; null otherwise */
            Long assignmentTradeId,
            /* FEAT-014: EXERCISE_BUY / EXERCISE_SELL: the exercise (option trade id) that generated it; null otherwise */
            Long exerciseTradeId,
            /* FEAT-013: false for generated records (edit / delete through their assignment / exercise) */
            boolean editable,
            boolean deletable) {
    }

    public record StockTradePageResponse(List<StockTradeResponse> items, int page, int size, long totalItems,
                                         int totalPages) {
    }

    public record StockLotResponse(long tradeId, StockTradeType type, Instant tradedAt, String tradedAtLocal,
                                   LocalDate tradeDate, int originalShares, int remainingShares,
                                   BigDecimal originalCost, BigDecimal remainingCost, String note) {
    }

    /** FEAT-039: {@code coveredShares} = shares covered by CHECKED short calls now; available = shares - covered. */
    public record StockPositionResponse(String symbol, long shares, long coveredShares, long availableShares,
                                        BigDecimal totalCost, BigDecimal averageCost,
                                        int lotCount, List<StockLotResponse> lots,
                                        /* FEAT-016: current price (one set for all accounts) */
                                        com.optionmgr.quote.service.QuoteViews.UnderlyingQuote quote,
                                        /* FEAT-017: valued with quote.price (null unless unrealizedStatus = OK) */
                                        BigDecimal marketValue, BigDecimal unrealizedPnl,
                                        BigDecimal unrealizedPnlPercent,
                                        com.optionmgr.valuation.domain.ValuationStatus unrealizedStatus,
                                        String unrealizedLabel) {
    }

    public record StockPositionsResponse(int positionCount, List<StockPositionResponse> positions) {
    }
}
