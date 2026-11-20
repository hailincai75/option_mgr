package db.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V18 (FEAT-051, Q255 A): stores the lots every existing BTC / STC closes, as derived before FEAT-051 - FIFO whole
 * lots in replay order {@code (traded_at, id)} per account and contract (symbol + expiration date + strike + type).
 *
 * <p>REAL-DATA MIGRATION: only inserts into {@code option_close_lot} (created by V17); no existing row of any table is
 * changed, so every close keeps its covered lots, realized P&amp;L, cash and frozen cash.
 *
 * <p>The matcher is deliberately self-contained (it does not use the application's {@code ContractReplay}), so that
 * later changes to domain code can never change what this migration produced. Opens (BTO / STO) are queued; BTC /
 * STC / EXP / ASG / EXR take whole lots from the front until their contracts are reached exactly. It fails - the
 * migration is rolled back and the application does not start; restore the Q60 backup
 * {@code <db>-backup-before-V18-...zip} - when a record cannot be matched to whole lots, or a BTC's stored frozen
 * change differs from minus its lots' frozen cash (data kept consistent by Q39 corrections; neither should occur).
 */
public class V18__Backfill_option_close_lots extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V18__Backfill_option_close_lots.class);

    private record Lot(long id, int contracts, BigDecimal frozen) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection con = context.getConnection();
        Map<String, BigDecimal> frozenByRecord = new HashMap<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT source_type, source_id, frozen_change FROM cash_ledger_entry "
                        + "WHERE source_type IN ('OPTION_OPEN', 'OPTION_CLOSE')")) {
            while (rs.next()) {
                String key = rs.getString(1) + ":" + rs.getLong(2);
                if (frozenByRecord.put(key, rs.getBigDecimal(3)) != null) {
                    throw new IllegalStateException("V18: more than one ledger entry for " + key);
                }
            }
        }

        List<long[]> rows = new ArrayList<>(); // close id, lot id, account id
        int closes = 0;
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT id, account_id, symbol, expiration_date, strike, option_type, action, contracts "
                        + "FROM option_trade "
                        + "ORDER BY account_id, symbol, expiration_date, strike, option_type, traded_at, id")) {
            String contract = null;
            Deque<Lot> lots = new ArrayDeque<>();
            while (rs.next()) {
                long id = rs.getLong("id");
                long accountId = rs.getLong("account_id");
                String key = accountId + "|" + rs.getString("symbol") + "|" + rs.getObject("expiration_date") + "|"
                        + rs.getBigDecimal("strike").stripTrailingZeros().toPlainString() + "|"
                        + rs.getString("option_type");
                if (!Objects.equals(key, contract)) {
                    contract = key;
                    lots.clear();
                }
                String action = rs.getString("action");
                int contracts = rs.getInt("contracts");
                if (action.equals("BTO") || action.equals("STO")) {
                    BigDecimal frozen = frozenByRecord.get("OPTION_OPEN:" + id);
                    if (frozen == null) {
                        throw new IllegalStateException("V18: opening trade #" + id + " has no ledger entry");
                    }
                    lots.addLast(new Lot(id, contracts, frozen));
                    continue;
                }
                // BTC / STC / EXP / ASG / EXR: the earliest whole lots, exactly
                List<Lot> covered = new ArrayList<>();
                int sum = 0;
                while (sum < contracts && !lots.isEmpty()) {
                    Lot l = lots.removeFirst();
                    covered.add(l);
                    sum += l.contracts();
                }
                if (sum != contracts) {
                    throw new IllegalStateException("V18: " + action + " #" + id + " (" + contracts + " contracts, "
                            + "contract " + key + ") does not match the earliest whole lots (matched " + sum + ")");
                }
                if (action.equals("BTC")) {
                    BigDecimal stored = frozenByRecord.get("OPTION_CLOSE:" + id);
                    BigDecimal released = covered.stream().map(Lot::frozen).reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (stored == null || stored.compareTo(released.negate()) != 0) {
                        throw new IllegalStateException("V18: BTC #" + id + " frozen change " + stored
                                + " differs from minus its FIFO lots' frozen cash " + released.negate());
                    }
                }
                if (action.equals("BTC") || action.equals("STC")) {
                    closes++;
                    for (Lot l : covered) {
                        rows.add(new long[] {id, l.id(), accountId});
                    }
                }
            }
        }

        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO option_close_lot (close_trade_id, lot_trade_id, account_id) VALUES (?, ?, ?)")) {
            for (long[] r : rows) {
                ps.setLong(1, r[0]);
                ps.setLong(2, r[1]);
                ps.setLong(3, r[2]);
                ps.addBatch();
            }
            if (!rows.isEmpty()) {
                ps.executeBatch();
            }
        }
        log.warn("V18: stored the FIFO lots of {} existing closes ({} rows)", closes, rows.size());
    }
}
