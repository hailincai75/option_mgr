package com.optionmgr.trade.web;

import com.optionmgr.account.web.AccountRef;
import com.optionmgr.calendar.domain.AdhocClosureAlerts;
import com.optionmgr.trade.service.OptionPositionService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FEAT-042 rule 4: ad-hoc closure alerts of the account (home page and positions page). Computed from the same
 * position summary as {@code GET /api/option-positions}, so counts always match the row tags. No dismissal (Q128).
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

    public record AlertResponse(LocalDate date, String eventName, int affectedPositionCount, String message) {
        static AlertResponse of(AdhocClosureAlerts.Alert a) {
            return new AlertResponse(a.date(), a.eventName(), a.affectedPositionCount(), a.message());
        }
    }

    @GetMapping
    public AlertsResponse alerts(AccountRef account) {
        return new AlertsResponse(positions.currentPositions(account.id()).adhocClosureAlerts().stream()
                .map(AlertResponse::of).toList());
    }
}
