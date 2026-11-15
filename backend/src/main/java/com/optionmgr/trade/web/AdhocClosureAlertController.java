package com.optionmgr.trade.web;

import com.optionmgr.account.web.AccountScope;
import com.optionmgr.calendar.domain.AdhocClosureAlerts;
import com.optionmgr.trade.service.OptionPositionService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FEAT-042 rule 4: ad-hoc closure alerts of the account (home page and positions page). Computed from the same
 * position summary as {@code GET /api/option-positions}, so counts always match the row tags. No dismissal (Q128).
 * FEAT-035: with {@code X-Account-Id: ALL}, counts of all accounts with the split by account (Q221).
 */
@RestController
@RequestMapping("/api/adhoc-closure-alerts")
public class AdhocClosureAlertController {

    private final OptionPositionService positions;

    public AdhocClosureAlertController(OptionPositionService positions) {
        this.positions = positions;
    }

    public record AlertsResponse(List<AlertResponse> alerts) {
    }

    public record AlertResponse(LocalDate date, String eventName, int affectedPositionCount,
                                List<AccountCountResponse> byAccount, String message) {
        static AlertResponse of(AdhocClosureAlerts.AccountsAlert a) {
            return new AlertResponse(a.date(), a.eventName(), a.affectedPositionCount(),
                    a.byAccount().stream().map(c -> new AccountCountResponse(c.accountId(), c.accountName(),
                            c.affectedPositionCount())).toList(),
                    a.message());
        }
    }

    /** FEAT-035: one account's affected positions on the date (accounts with at least one, by id ascending). */
    public record AccountCountResponse(long accountId, String accountName, int affectedPositionCount) {
    }

    @GetMapping
    public AlertsResponse alerts(AccountScope scope) {
        List<OptionPositionService.PositionSummary> summaries = positions.currentPositions(scope.ids());
        List<AdhocClosureAlerts.AccountPositions> accounts = new ArrayList<>();
        for (int i = 0; i < summaries.size(); i++) {
            AccountScope.Member m = scope.accounts().get(i);
            accounts.add(new AdhocClosureAlerts.AccountPositions(m.id(), m.name(), summaries.get(i).positions()
                    .stream().map(p -> p.contract().expirationDate()).toList()));
        }
        Map<LocalDate, String> closures = summaries.get(0).adhocClosures();
        return new AlertsResponse(AdhocClosureAlerts.alerts(closures, accounts, scope.all()).stream()
                .map(AlertResponse::of).toList());
    }
}
