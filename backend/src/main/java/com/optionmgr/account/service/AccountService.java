package com.optionmgr.account.service;

import com.optionmgr.account.domain.AccountNames;
import com.optionmgr.account.repository.AccountEntity;
import com.optionmgr.account.repository.AccountRepository;
import com.optionmgr.audit.domain.AuditObjectType;
import com.optionmgr.audit.domain.AuditOperation;
import com.optionmgr.audit.domain.FieldChanges;
import com.optionmgr.audit.service.AuditService;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-033 accounts: list, create, rename (Q51, Q58), audited in the account itself (Q59). */
@Service
public class AccountService {

    public static final String NAME_TAKEN = "账户名称已存在";

    private final AccountRepository accounts;
    private final CashLedgerService ledger;
    private final AuditService audit;
    private final Clock clock;

    public AccountService(AccountRepository accounts, CashLedgerService ledger, AuditService audit, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AccountEntity> list() {
        return accounts.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public AccountEntity get(long id) {
        return accounts.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public boolean exists(long id) {
        return accounts.existsById(id);
    }

    /** New account with no records (cash 0.00); its creation is the first record of its own audit log. */
    @Transactional
    public AccountEntity create(String name, String brokerNote) {
        requireUniqueName(name, null);
        AccountEntity account = accounts.saveAndFlush(new AccountEntity(name, brokerNote, clock.instant()));
        ledger.openAccount(account.getId());
        audit.record(new AuditService.NewAuditRecord(account.getId(), AuditOperation.CREATE,
                AuditObjectType.ACCOUNT, account.getId(), summary(account.getName()), null, snapshot(account),
                List.of(), List.of()));
        return account;
    }

    @Transactional
    public AccountEntity update(long id, String name, String brokerNote) {
        AccountEntity account = accounts.findById(id).orElseThrow(() -> notFound(id));
        String newName = AccountNames.normalize(name);
        String newNote = AccountNames.normalizeBrokerNote(brokerNote);
        if (newName.equals(account.getName()) && Objects.equals(newNote, account.getBrokerNote())) {
            throw new ApiException(ErrorCode.NO_CHANGES, "没有修改");
        }
        requireUniqueName(name, id);
        Map<String, Object> before = snapshot(account);
        account.rename(name, brokerNote);
        accounts.flush();
        Map<String, Object> after = snapshot(account);
        audit.record(new AuditService.NewAuditRecord(id, AuditOperation.UPDATE, AuditObjectType.ACCOUNT, id,
                summary((String) before.get("name")), before, after,
                new FieldChanges()
                        .compare("name", "名称", (String) before.get("name"), (String) after.get("name"))
                        .compare("brokerNote", "券商备注", (String) before.get("brokerNote"),
                                (String) after.get("brokerNote"))
                        .list(),
                List.of()));
        return account;
    }

    private void requireUniqueName(String name, Long exceptId) {
        accounts.findByNameKey(AccountNames.key(name))
                .filter(other -> !other.getId().equals(exceptId))
                .ifPresent(other -> {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                            List.of(new ErrorResponse.FieldError("name", NAME_TAKEN)));
                });
    }

    private static ApiException notFound(long id) {
        return new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + id + " not found");
    }

    private static String summary(String name) {
        return "账户 " + name;
    }

    private static Map<String, Object> snapshot(AccountEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("brokerNote", a.getBrokerNote());
        return m;
    }
}
