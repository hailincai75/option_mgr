package com.optionmgr.cash.web;

import com.optionmgr.cash.domain.CashBalance;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.cash.service.CashTransferEditService;
import com.optionmgr.cash.service.CashTransferService;
import com.optionmgr.cash.service.LedgerPage;
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
    public CashSummaryResponse summary() {
        CashBalance b = ledger.currentBalance();
        return new CashSummaryResponse(b.total(), b.frozen(), b.available());
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse createTransfer(@Valid @RequestBody CreateTransferRequest req) {
        CashTransferService.Result r = transfers.create(new CashTransferService.Command(
                req.type(), req.amount(), req.occurredAtLocal(), req.remark()));
        var t = r.transfer();
        return new TransferResponse(t.getId(), t.getType(), t.getAmount(), t.getOccurredAt(), t.getRemark(),
                r.ledgerEntry().getId(), t.getCreatedAt());
    }

    /** FEAT-005: edit a deposit / withdrawal (same body as POST). */
    @PutMapping("/transfers/{id}")
    public TransferResponse updateTransfer(@PathVariable long id, @Valid @RequestBody CreateTransferRequest req) {
        CashTransferService.Result r = edits.update(id, new CashTransferService.Command(
                req.type(), req.amount(), req.occurredAtLocal(), req.remark()));
        var t = r.transfer();
        return new TransferResponse(t.getId(), t.getType(), t.getAmount(), t.getOccurredAt(), t.getRemark(),
                r.ledgerEntry().getId(), t.getCreatedAt());
    }

    /** FEAT-005: delete a deposit / withdrawal. */
    @DeleteMapping("/transfers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransfer(@PathVariable long id) {
        edits.delete(id);
    }

    @GetMapping("/ledger")
    public LedgerPageResponse ledger(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 必须 ≥ 1") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "size 必须在 1 到 100 之间")
            @Max(value = 100, message = "size 必须在 1 到 100 之间") int size) {
        LedgerPage p = ledger.ledgerPage(page, size);
        return new LedgerPageResponse(
                p.items().stream().map(l -> new LedgerEntryResponse(l.id(), l.occurredAt(), l.sourceType(),
                        l.sourceId(), l.amount(), l.balanceAfter(), l.frozenChange(), l.description(),
                        l.remark())).toList(),
                p.page(), p.size(), p.totalItems(), p.totalPages());
    }
}
