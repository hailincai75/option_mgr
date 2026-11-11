package com.optionmgr.cash.service;

import com.optionmgr.cash.domain.CashBalance;
import com.optionmgr.cash.domain.CashMovement;
import com.optionmgr.cash.domain.CashReplay;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.InsertionCheck;
import com.optionmgr.cash.domain.ReplayStep;
import com.optionmgr.cash.repository.CashAccountEntity;
import com.optionmgr.cash.repository.CashAccountRepository;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.repository.CashLedgerEntryRepository;
import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unified cash ledger: current balance, paged history with running balance, and the building blocks
 * every cash-changing feature uses inside its own transaction:
 * <ol>
 *   <li>{@link #checkInsertion} - lock the account and replay with the new movement (Q19);</li>
 *   <li>save the originating record (transfer, trade, ...);</li>
 *   <li>{@link #append} - write the ledger entry referencing it.</li>
 * </ol>
 */
@Service
public class CashLedgerService {

    private final CashAccountRepository accounts;
    private final CashLedgerEntryRepository entries;
    private final Clock clock;

    public CashLedgerService(CashAccountRepository accounts, CashLedgerEntryRepository entries, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.clock = clock;
    }

    /** Serializes cash-changing transactions; the lock is held until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAccount() {
        accounts.lockById(CashAccountEntity.SINGLE_ACCOUNT_ID)
                .orElseThrow(() -> new IllegalStateException("cash account row missing (migration V2)"));
    }

    /**
     * Locks the account and replays all entries with a not-yet-stored movement inserted at {@code occurredAt}
     * (after existing entries at the same instant). Does not write anything.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InsertionCheck checkInsertion(Instant occurredAt, BigDecimal cashChange, BigDecimal frozenChange) {
        lockAccount();
        return CashReplay.checkInsertion(loadMovements(),
                CashMovement.pending(occurredAt, Money.of(cashChange), Money.of(frozenChange)));
    }

    /** Appends a ledger entry; its id becomes its entry order. Caller must have checked and locked first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CashLedgerEntryEntity append(NewLedgerEntry entry) {
        lockAccount();
        return entries.save(new CashLedgerEntryEntity(entry.occurredAt(), entry.sourceType(), entry.sourceId(),
                Money.of(entry.amount()), Money.of(entry.frozenChange()), entry.description(), entry.remark(),
                clock.instant()));
    }

    /** The ledger entry written for an originating record (e.g. a trade), if any. */
    @Transactional(readOnly = true)
    public Optional<CashLedgerEntryEntity> findBySource(CashSourceType sourceType, long sourceId) {
        return entries.findFirstBySourceTypeAndSourceIdOrderByIdAsc(sourceType, sourceId);
    }

    /**
     * First ledger entry per source id, for originating records of the given kinds that share one id space
     * (e.g. all option trade kinds reference option_trade.id). Records without an entry are absent from the map.
     */
    @Transactional(readOnly = true)
    public Map<Long, CashLedgerEntryEntity> findBySources(Collection<CashSourceType> sourceTypes,
                                                          Collection<Long> sourceIds) {
        Map<Long, CashLedgerEntryEntity> bySource = new HashMap<>();
        if (sourceIds.isEmpty()) {
            return bySource;
        }
        for (CashLedgerEntryEntity e : entries.findBySourceTypeInAndSourceIdInOrderByIdAsc(sourceTypes, sourceIds)) {
            bySource.putIfAbsent(e.getSourceId(), e);
        }
        return bySource;
    }

    /** Current total / frozen / available cash (every entry is not later than now). */
    @Transactional(readOnly = true)
    public CashBalance currentBalance() {
        return CashReplay.finalBalance(loadMovements());
    }

    /** Ledger newest first (same instant: later entry first), 1-based page, running total recomputed by replay. */
    @Transactional(readOnly = true)
    public LedgerPage ledgerPage(int page, int size) {
        List<CashLedgerEntryEntity> ordered = entries.findAllByOrderByOccurredAtAscIdAsc();
        List<CashMovement> movements = ordered.stream().map(CashLedgerEntryEntity::toMovement).toList();
        List<ReplayStep> steps = CashReplay.replay(movements);

        List<LedgerLine> lines = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            CashLedgerEntryEntity e = ordered.get(i);
            ReplayStep step = steps.get(i);
            if (step.movement().sequence() != e.getId()) {
                throw new IllegalStateException("ledger replay order mismatch");
            }
            lines.add(new LedgerLine(e.getId(), e.getOccurredAt(), e.getSourceType(), e.getSourceId(),
                    e.getAmount(), step.after().total(), e.getFrozenChange(), e.getDescription(), e.getRemark()));
        }
        Collections.reverse(lines);

        long total = lines.size();
        int totalPages = (int) ((total + size - 1) / size);
        long from = (long) (page - 1) * size;
        List<LedgerLine> items = from >= total
                ? List.of()
                : List.copyOf(lines.subList((int) from, (int) Math.min(from + size, total)));
        return new LedgerPage(items, page, size, total, totalPages);
    }

    private List<CashMovement> loadMovements() {
        return entries.findAllByOrderByOccurredAtAscIdAsc().stream().map(CashLedgerEntryEntity::toMovement).toList();
    }
}
