package com.optionmgr.cash.web;

import com.optionmgr.account.domain.AccountRows;
import com.optionmgr.account.web.AccountRef;
import com.optionmgr.account.web.AccountScope;
import com.optionmgr.cash.web.CashDtos.AccountCashResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import com.optionmgr.cash.domain.CashBalance;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.cash.service.CashTransferEditService;
import com.optionmgr.cash.service.CashTransferService;
import com.optionmgr.cash.service.LedgerLine;
import com.optionmgr.cash.web.CashDtos.CashSummaryResponse;
import com.optionmgr.cash.web.CashDtos.CreateTransferRequest;
import com.optionmgr.cash.web.CashDtos.LedgerEntryResponse;
import com.optionmgr.cash.web.CashDtos.LedgerPageResponse;
import com.optionmgr.cash.web.CashDtos.TransferResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-007 cash account API; FEAT-005 edit / delete of deposits and withdrawals. */
@RestController
@RequestMapping("/api/cash")
public class CashController {

    private final CashLedgerService ledger;
    private final CashTransferService transfers;
    private final CashTransferEditService edits;
    public CashController(CashLedgerService ledger, CashTransferService transfers, CashTransferEditService edits) {
        this.ledger = ledger;
        this.transfers = transfers;
        this.edits = edits;
    }

    @GetMapping("/summary")
    public CashSummaryResponse summary(AccountScope scope) {
        // FEAT-035: every account of the scope (accounts without records included, 0.00); top level = exact sums
        CashBalance sum = CashBalance.EMPTY;
        List<AccountCashResponse> rows = new ArrayList<>();
        for (AccountScope.Member m : scope.accounts()) {
            CashBalance b = ledger.currentBalance(m.id());
            sum = sum.plus(b);
            rows.add(new AccountCashResponse(m.id(), m.name(), b.total(), b.frozen(), b.available()));
        }
        return new CashSummaryResponse(sum.total(), sum.frozen(), sum.available(), rows);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse createTransfer(AccountRef account, @Valid @RequestBody CreateTransferRequest req) {
        CashTransferService.Result r = transfers.create(account.id(), new CashTransferService.Command(
                req.type(), req.amount(), req.occurredAtLocal(), req.remark()));
        var t = r.transfer();
        return new TransferResponse(t.getId(), t.getType(), t.getAmount(), t.getOccurredAt(), t.getRemark(),
                r.ledgerEntry().getId(), t.getCreatedAt());
    }

    /** FEAT-005: edit a deposit / withdrawal (same body as POST). */
    @PutMapping("/transfers/{id}")
    public TransferResponse updateTransfer(AccountRef account, @PathVariable long id, @Valid @RequestBody CreateTransferRequest req) {
        CashTransferService.Result r = edits.update(account.id(), id, new CashTransferService.Command(
                req.type(), req.amount(), req.occurredAtLocal(), req.remark()));
        var t = r.transfer();
        return new TransferResponse(t.getId(), t.getType(), t.getAmount(), t.getOccurredAt(), t.getRemark(),
                r.ledgerEntry().getId(), t.getCreatedAt());
    }

    /** FEAT-005: delete a deposit / withdrawal. */
    @DeleteMapping("/transfers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransfer(AccountRef account, @PathVariable long id) {
        edits.delete(account.id(), id);
    }

    @GetMapping("/ledger")
    public LedgerPageResponse ledger(
            AccountScope scope,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 必须 ≥ 1") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "size 必须在 1 到 100 之间")
            @Max(value = 100, message = "size 必须在 1 到 100 之间") int size) {
        // FEAT-046: each account replayed on its own (balanceAfter = the row's own account, Q228), then merged
        // (occurredAt desc, id desc, account id asc) and paged as one list
        List<AccountRows.Row<LedgerLine>> rows = new ArrayList<>();
        for (AccountScope.Member m : scope.accounts()) {
            ledger.lines(m.id()).forEach(l -> rows.add(new AccountRows.Row<>(m.id(), m.name(), l)));
        }
        AccountRows.Page<AccountRows.Row<LedgerLine>> p = AccountRows.page(AccountRows.sorted(rows, LEDGER_ORDER),
                page, size);
        // hasAuditLog of the page's deposit / withdrawal lines, per account
        Map<Long, List<LedgerLine>> byAccount = new LinkedHashMap<>();
        p.items().forEach(r -> byAccount.computeIfAbsent(r.accountId(), a -> new ArrayList<>()).add(r.value()));
        Map<Long, LedgerLine> flagged = new HashMap<>();
        byAccount.forEach((accountId, lines) -> ledger.withTransferFlags(accountId, lines)
                .forEach(l -> flagged.put(l.id(), l)));
        return new LedgerPageResponse(p.items().stream().map(r -> {
            LedgerLine l = flagged.get(r.value().id());
            return new LedgerEntryResponse(r.accountId(), r.accountName(), l.id(), l.occurredAt(), l.sourceType(),
                    l.sourceId(), l.amount(), l.balanceAfter(), l.frozenChange(), l.description(), l.remark(),
                    l.hasAuditLog());
        }).toList(), page, size, p.totalItems(), p.totalPages());
    }

    /** FEAT-007 ledger order: newest first, same instant: later entry first (ledger ids are global, FEAT-033). */
    private static final Comparator<LedgerLine> LEDGER_ORDER = Comparator
            .comparing(LedgerLine::occurredAt, Comparator.reverseOrder())
            .thenComparing(LedgerLine::id, Comparator.reverseOrder());
}
