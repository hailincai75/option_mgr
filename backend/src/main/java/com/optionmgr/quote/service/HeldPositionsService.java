package com.optionmgr.quote.service;

import com.optionmgr.account.repository.AccountEntity;
import com.optionmgr.account.repository.AccountRepository;
import com.optionmgr.stock.domain.StockPosition;
import com.optionmgr.stock.service.StockPositionService;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.service.OptionPositionService;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-016 rule 1: what is held now in any account (one quote set for all accounts). */
@Service
public class HeldPositionsService {

    /**
     * @param symbols      option underlyings (expired-unprocessed included) + stock symbols
     * @param contracts    option contracts with an open position (expired-unprocessed included)
     * @param stockSymbols symbols with shares &gt; 0
     */
    public record Held(Set<String> symbols, Set<ContractKey> contracts, Set<String> stockSymbols) {

        /**
         * Q150: what a run fetches - contracts not expired yet (expiration &gt;= today, New York) and the symbols with
         * such a contract or with shares, index symbols excluded.
         */
        public Tracked tracked(LocalDate today, Predicate<String> isIndex) {
            Set<ContractKey> live = contracts.stream().filter(c -> !c.expirationDate().isBefore(today))
                    .filter(c -> !isIndex.test(c.symbol())).collect(Collectors.toSet());
            Set<String> syms = new TreeSet<>();
            live.forEach(c -> syms.add(c.symbol()));
            stockSymbols.stream().filter(s -> !isIndex.test(s)).forEach(syms::add);
            return new Tracked(Set.copyOf(syms), Set.copyOf(live));
        }
    }

    /** Q150 / Q149: tracked symbols and contracts of a run. */
    public record Tracked(Set<String> symbols, Set<ContractKey> contracts) {

        public Set<ContractKey> contractsOf(String symbol) {
            return contracts.stream().filter(c -> c.symbol().equals(symbol)).collect(Collectors.toSet());
        }
    }

    private final AccountRepository accounts;
    private final OptionPositionService options;
    private final StockPositionService stocks;

    public HeldPositionsService(AccountRepository accounts, OptionPositionService options, StockPositionService stocks) {
        this.accounts = accounts;
        this.options = options;
        this.stocks = stocks;
    }

    @Transactional(readOnly = true)
    public Held current() {
        Set<String> symbols = new HashSet<>();
        Set<ContractKey> contracts = new HashSet<>();
        Set<String> stockSymbols = new HashSet<>();
        for (AccountEntity a : accounts.findAllByOrderByIdAsc()) {
            Set<ContractKey> open = options.openContracts(a.getId());
            contracts.addAll(open);
            open.forEach(c -> symbols.add(c.symbol()));
            for (StockPosition p : stocks.currentPositions(a.getId())) {
                symbols.add(p.symbol());
                stockSymbols.add(p.symbol());
            }
        }
        return new Held(Set.copyOf(symbols), Set.copyOf(contracts), Set.copyOf(stockSymbols));
    }
}
