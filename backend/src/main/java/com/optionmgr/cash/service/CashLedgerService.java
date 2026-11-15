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
import com.optionmgr.cash.repository.CashTransferEntity;
import com.optionmgr.cash.repository.CashTransferRepository;
import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unified cash ledger of one account (FEAT-033: everything is per account): current balance, paged history with
 * running balance, and the building blocks every cash-changing feature uses inside its own transaction:
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
    private final CashTransferRepository transfers;
    private final Clock clock;

    public CashLedgerService(CashAccountRepository accounts, CashLedgerEntryRepository entries,
                             CashTransferRepository transfers, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.transfers = transfers;
        this.clock = clock;
    }

    /** FEAT-033: creates the cash-account (lock) row of a new account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openAccount(long accountId) {
        accounts.save(new CashAccountEntity(accountId, "USD", clock.instant()));
    }

    /** Serializes the account's cash-changing transactions; held until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAccount(long accountId) {
        accounts.lockById(accountId)
                .orElseThrow(() -> new IllegalStateException("cash account row missing for account " + accountId));
    }

    /** Locks every account (e2e reset). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAllAccounts() {
        accounts.lockAll();
    }

    /**
     * Locks the account and replays its entries with a not-yet-stored movement inserted at {@code occurredAt}
     * (after existing entries at the same instant). Does not write anything.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InsertionCheck checkInsertion(long accountId, Instant occurredAt, BigDecimal cashChange,
                                         BigDecimal frozenChange) {
        return checkInsertion(accountId, occurredAt, cashChange, frozenChange, Map.of());
    }

    /**
     * As above, with the frozen change of some existing entries replaced (ledger id -&gt; new frozen change) - the
     * state after {@link #correctFrozenChanges} would apply them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InsertionCheck checkInsertion(long accountId, Instant occurredAt, BigDecimal cashChange,
                                         BigDecimal frozenChange, Map<Long, BigDecimal> frozenOverrides) {
        lockAccount(accountId);
        List<CashMovement> movements = loadMovements(accountId).stream()
                .map(m -> frozenOverrides.containsKey(m.sequence())
                        ? new CashMovement(m.occurredAt(), m.sequence(), m.cashChange(),
                                Money.of(frozenOverrides.get(m.sequence())))
                        : m)
                .toList();
        return CashReplay.checkInsertion(movements,
                CashMovement.pending(occurredAt, Money.of(cashChange), Money.of(frozenChange)));
    }

    /** Applies frozen-change corrections (ledger id -&gt; new value) to entries of the account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void correctFrozenChanges(long accountId, Map<Long, BigDecimal> frozenOverrides) {
        lockAccount(accountId);
        frozenOverrides.forEach((id, frozen) -> entry(accountId, id).correctFrozenChange(Money.of(frozen)));
    }

    /**
     * FEAT-005: locks the account and replays its whole ledger with some entries replaced (by ledger id; the
     * replacement keeps the id as its entry order) and some removed. Does not write anything.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ReplayStep> replayWithChanges(long accountId, Map<Long, CashMovement> replacements,
                                              Collection<Long> removals) {
        lockAccount(accountId);
        List<CashMovement> movements = new ArrayList<>();
        for (CashMovement m : loadMovements(accountId)) {
            if (removals.contains(m.sequence())) {
                continue;
            }
            movements.add(replacements.getOrDefault(m.sequence(), m));
        }
        return CashReplay.replay(movements);
    }

    /** FEAT-005: updates an entry of the account in place (keeps its id = entry order). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateEntry(long accountId, long id, Instant occurredAt, CashSourceType sourceType,
                            BigDecimal amount, BigDecimal frozenChange, String description, String remark) {
        lockAccount(accountId);
        entry(accountId, id).update(occurredAt, sourceType, Money.of(amount), Money.of(frozenChange), description,
                remark);
    }

    /** FEAT-005: deletes the entry of a deleted record of the account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteEntry(long accountId, long id) {
        lockAccount(accountId);
        entries.delete(entry(accountId, id));
    }

    /** Appends a ledger entry; its id becomes its entry order. Caller must have checked and locked first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CashLedgerEntryEntity append(NewLedgerEntry entry) {
        lockAccount(entry.accountId());
        return entries.save(new CashLedgerEntryEntity(entry.accountId(), entry.occurredAt(), entry.sourceType(),
                entry.sourceId(), Money.of(entry.amount()), Money.of(entry.frozenChange()), entry.description(),
                entry.remark(), clock.instant()));
    }

    /**
     * First ledger entry per source id within the account, for originating records of the given kinds that share
     * one id space (e.g. all option trade kinds reference option_trade.id). Records without an entry are absent.
     */
    @Transactional(readOnly = true)
    public Map<Long, CashLedgerEntryEntity> findBySources(long accountId, Collection<CashSourceType> sourceTypes,
                                                          Collection<Long> sourceIds) {
        Map<Long, CashLedgerEntryEntity> bySource = new HashMap<>();
        if (sourceIds.isEmpty()) {
            return bySource;
        }
        for (CashLedgerEntryEntity e : entries.findByAccountIdAndSourceTypeInAndSourceIdInOrderByIdAsc(accountId,
                sourceTypes, sourceIds)) {
            bySource.putIfAbsent(e.getSourceId(), e);
        }
        return bySource;
    }

    /** Current total / frozen / available cash of the account (every entry is not later than now). */
    @Transactional(readOnly = true)
    public CashBalance currentBalance(long accountId) {
        return CashReplay.finalBalance(loadMovements(accountId));
    }

    /**
     * Every ledger line of the account, newest first (same instant: later entry first), each with the account's own
     * running total after it (recomputed by replay); {@code hasAuditLog} not yet set (see
     * {@link #withTransferFlags}). FEAT-046: the ledger endpoint merges and pages these lines of every account.
     */
    @Transactional(readOnly = true)
    public List<LedgerLine> lines(long accountId) {
        List<CashLedgerEntryEntity> ordered = entries.findAllByAccountIdOrderByOccurredAtAscIdAsc(accountId);
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
                    e.getAmount(), step.after().total(), e.getFrozenChange(), e.getDescription(), e.getRemark(),
                    false));
        }
        Collections.reverse(lines);
        return lines;
    }

    /**
     * hasAuditLog of one page (lines of one account): DEPOSIT / WITHDRAWAL lines take the stored flag of their cash
     * transfer (one query for the page's transfers, none when the page has none); every other line stays false.
     */
    @Transactional(readOnly = true)
    public List<LedgerLine> withTransferFlags(long accountId, List<LedgerLine> pageLines) {
        Set<Long> transferIds = pageLines.stream().filter(CashLedgerService::isTransfer).map(LedgerLine::sourceId)
                .collect(Collectors.toSet());
        if (transferIds.isEmpty()) {
            return List.copyOf(pageLines);
        }
        Set<Long> flagged = transfers.findAllById(transferIds).stream()
                .filter(t -> t.getAccountId() == accountId && t.hasAuditLog())
                .map(CashTransferEntity::getId).collect(Collectors.toSet());
        return pageLines.stream().map(l -> isTransfer(l) && flagged.contains(l.sourceId())
                ? new LedgerLine(l.id(), l.occurredAt(), l.sourceType(), l.sourceId(), l.amount(), l.balanceAfter(),
                        l.frozenChange(), l.description(), l.remark(), true)
                : l).toList();
    }

    private static boolean isTransfer(LedgerLine l) {
        return l.sourceType() == CashSourceType.DEPOSIT || l.sourceType() == CashSourceType.WITHDRAWAL;
    }

    private CashLedgerEntryEntity entry(long accountId, long id) {
        CashLedgerEntryEntity e = entries.findById(id)
                .orElseThrow(() -> new IllegalStateException("ledger entry " + id + " missing"));
        if (e.getAccountId() != accountId) {
            throw new IllegalStateException("ledger entry " + id + " is not in account " + accountId);
        }
        return e;
    }

    private List<CashMovement> loadMovements(long accountId) {
        return entries.findAllByAccountIdOrderByOccurredAtAscIdAsc(accountId).stream()
                .map(CashLedgerEntryEntity::toMovement).toList();
    }
}
