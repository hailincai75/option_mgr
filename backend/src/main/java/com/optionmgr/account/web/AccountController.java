package com.optionmgr.account.web;

import com.optionmgr.account.domain.AccountNames;
import com.optionmgr.account.repository.AccountEntity;
import com.optionmgr.account.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-033 accounts: list, read, create, rename. No delete (Q58). Not account-scoped (no X-Account-Id). */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Name / broker note are trimmed before validation (" 小账户 " is "小账户"). */
    public record AccountRequest(
            @NotBlank(message = "账户名称不能为空")
            @Size(max = AccountNames.MAX_NAME_LENGTH, message = "账户名称最多 30 个字符")
            String name,

            @Size(max = AccountNames.MAX_BROKER_NOTE_LENGTH, message = "券商备注最多 50 个字符")
            String brokerNote) {

        public AccountRequest {
            name = AccountNames.normalize(name);
            brokerNote = AccountNames.normalizeBrokerNote(brokerNote);
        }
    }

    public record AccountResponse(long id, String name, String brokerNote, Instant createdAt) {
        static AccountResponse of(AccountEntity a) {
            return new AccountResponse(a.getId(), a.getName(), a.getBrokerNote(), a.getCreatedAt());
        }
    }

    @GetMapping
    public List<AccountResponse> list() {
        return accounts.list().stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable long id) {
        return AccountResponse.of(accounts.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody AccountRequest req) {
        return AccountResponse.of(accounts.create(req.name(), req.brokerNote()));
    }

    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable long id, @Valid @RequestBody AccountRequest req) {
        return AccountResponse.of(accounts.update(id, req.name(), req.brokerNote()));
    }
}
