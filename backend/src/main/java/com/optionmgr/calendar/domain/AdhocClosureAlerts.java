package com.optionmgr.calendar.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FEAT-042 rules 3-4 (pure Java): one alert per ACTIVE ad-hoc closure date with at least one affected position
 * (a current option position expiring on that date), by date ascending. Nothing to dismiss (Q128); no affected
 * positions = no alert (Q129).
 */
public final class AdhocClosureAlerts {

    private AdhocClosureAlerts() {
    }

    /** One alert line; {@code message} is the full text to display (without the trailing link). */
    public record Alert(LocalDate date, String eventName, int affectedPositionCount, String message) {
    }

    /**
     * @param activeClosures      ACTIVE ad-hoc closures: date to eventName
     * @param positionExpirations expiration date of each current option position of the account (one per position)
     */
    public static List<Alert> alerts(Map<LocalDate, String> activeClosures, Collection<LocalDate> positionExpirations) {
        Map<LocalDate, Integer> counts = new TreeMap<>();
        for (LocalDate exp : positionExpirations) {
            if (activeClosures.containsKey(exp)) {
                counts.merge(exp, 1, Integer::sum);
            }
        }
        List<Alert> alerts = new ArrayList<>();
        counts.forEach((date, n) -> {
            String name = activeClosures.get(date);
            alerts.add(new Alert(date, name, n, message(date, name, n)));
        });
        return alerts;
    }

    public static String message(LocalDate date, String eventName, int count) {
        return message(date, eventName, count, "");
    }

    private static String message(LocalDate date, String eventName, int count, String byAccount) {
        return date + " 临时休市（" + eventName + "），你有 " + count
                + " 个持仓在该日到期" + byAccount + "，期权通常改为前一个交易日到期，请核对并调整到期日";
    }

    /** FEAT-035: one account and the expiration date of each of its current option positions. */
    public record AccountPositions(long accountId, String accountName, Collection<LocalDate> positionExpirations) {
    }

    /** FEAT-035 Q221: affected positions of one account on one closure date (count &gt;= 1). */
    public record AccountCount(long accountId, String accountName, int affectedPositionCount) {
    }

    /** FEAT-035: an alert with its per-account counts (accounts with at least one affected position, id ascending). */
    public record AccountsAlert(LocalDate date, String eventName, int affectedPositionCount,
                                List<AccountCount> byAccount, String message) {
    }

    /**
     * FEAT-035: alerts over several accounts. Counts are per row (one contract in two accounts counts 2, Q215).
     * {@code allAccounts} = the {@code X-Account-Id: ALL} view: the message then lists the counts by account after
     * "在该日到期", even when only one account is affected; otherwise the message is the FEAT-042 text.
     *
     * @param accounts accounts by id ascending
     */
    public static List<AccountsAlert> alerts(Map<LocalDate, String> activeClosures, List<AccountPositions> accounts,
                                             boolean allAccounts) {
        Map<LocalDate, List<AccountCount>> byDate = new TreeMap<>();
        for (AccountPositions a : accounts) {
            for (Alert alert : alerts(activeClosures, a.positionExpirations())) {
                byDate.computeIfAbsent(alert.date(), d -> new ArrayList<>())
                        .add(new AccountCount(a.accountId(), a.accountName(), alert.affectedPositionCount()));
            }
        }
        List<AccountsAlert> out = new ArrayList<>();
        byDate.forEach((date, counts) -> {
            String name = activeClosures.get(date);
            int total = counts.stream().mapToInt(AccountCount::affectedPositionCount).sum();
            String split = "";
            if (allAccounts) {
                StringBuilder s = new StringBuilder("（");
                for (int i = 0; i < counts.size(); i++) {
                    if (i > 0) {
                        s.append('、');
                    }
                    s.append(counts.get(i).accountName()).append(' ').append(counts.get(i).affectedPositionCount())
                            .append(" 个");
                }
                split = s.append('）').toString();
            }
            out.add(new AccountsAlert(date, name, total, List.copyOf(counts), message(date, name, total, split)));
        });
        return out;
    }
}
