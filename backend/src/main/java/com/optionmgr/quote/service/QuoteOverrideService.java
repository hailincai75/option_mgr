package com.optionmgr.quote.service;

import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.quote.domain.OptionState;
import com.optionmgr.quote.domain.UnderlyingState;
import com.optionmgr.quote.repository.OptionQuoteEntity;
import com.optionmgr.quote.repository.OptionQuoteRepository;
import com.optionmgr.quote.repository.UnderlyingQuoteEntity;
import com.optionmgr.quote.repository.UnderlyingQuoteRepository;
import com.optionmgr.trade.domain.ContractKey;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FEAT-016 rule 8 / Q140: manual underlying price and contract mark, one for all accounts. Setting requires a
 * current position (any account); clearing is idempotent. The next successful automatic value replaces them
 * ({@link com.optionmgr.quote.domain.QuoteMerge}).
 */
@Service
public class QuoteOverrideService {

    private final UnderlyingQuoteRepository underlyings;
    private final OptionQuoteRepository options;
    private final HeldPositionsService held;
    private final QuoteQueryService queries;
    private final QuoteWriteLock writeLock;
    private final TransactionTemplate tx;
    private final Clock clock;

    public QuoteOverrideService(UnderlyingQuoteRepository underlyings, OptionQuoteRepository options,
                                HeldPositionsService held, QuoteQueryService queries, QuoteWriteLock writeLock,
                                PlatformTransactionManager txManager, Clock clock) {
        this.underlyings = underlyings;
        this.options = options;
        this.held = held;
        this.queries = queries;
        this.writeLock = writeLock;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public QuoteViews.UnderlyingQuote setUnderlying(String symbol, BigDecimal price) {
        if (!held.current().symbols().contains(symbol)) {
            throw new ApiException(ErrorCode.QUOTE_TARGET_NOT_HELD, "当前没有 " + symbol + " 的持仓，不能设置手动价格");
        }
        locked(() -> {
            UnderlyingQuoteEntity row = underlyings.findById(symbol).orElseGet(() -> new UnderlyingQuoteEntity(symbol));
            row.update(row.toDomain().withManual(price, clock.instant()));
            underlyings.save(row);
        });
        return queries.book().underlying(symbol);
    }

    public void clearUnderlying(String symbol) {
        locked(() -> underlyings.findById(symbol).ifPresent(row -> {
            UnderlyingState s = row.toDomain();
            if (s.manual()) {
                row.update(s.withManual(null, null));
                underlyings.save(row);
            }
        }));
    }

    public QuoteViews.OptionQuote setOption(ContractKey contract, BigDecimal mark) {
        if (!held.current().contracts().contains(contract)) {
            throw new ApiException(ErrorCode.QUOTE_TARGET_NOT_HELD,
                    "当前没有 " + contract.label() + " 的持仓，不能设置手动价格");
        }
        locked(() -> {
            OptionQuoteEntity row = find(contract).orElseGet(() -> new OptionQuoteEntity(contract));
            row.update(row.toDomain().withManual(mark, clock.instant()));
            options.save(row);
        });
        return queries.book().option(contract);
    }

    public void clearOption(ContractKey contract) {
        locked(() -> find(contract).ifPresent(row -> {
            OptionState s = row.toDomain();
            if (s.manual()) {
                row.update(s.withManual(null, null));
                options.save(row);
            }
        }));
    }

    private Optional<OptionQuoteEntity> find(ContractKey contract) {
        return options.findAll().stream().filter(e -> e.contract().equals(contract)).findFirst();
    }

    private void locked(Runnable work) {
        writeLock.lock();
        try {
            tx.executeWithoutResult(s -> work.run());
        } finally {
            writeLock.unlock();
        }
    }
}
