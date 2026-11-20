package com.optionmgr.systemtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.OptionMgrApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * QA system test for FEAT-051 AC 8 / rule 6 (data entered before the upgrade keeps its closed lots, P&L, cash, frozen,
 * positions, closed records and ids; contract "迁移" V17 {@code option_close_lot} + V18 Java backfill by FIFO).
 *
 * <p>"Before the upgrade" is simulated on a temporary file database (JUnit temp dir — never backend/data): the
 * application writes the data through the API (FIFO-mode closes only, i.e. what the old version would have stored),
 * the test records every read, then removes what V17 / V18 created (drops {@code option_close_lot}, deletes the two
 * {@code flyway_schema_history} rows) so the database is exactly a V16 database with the same business rows. The next
 * start re-runs V17 + V18 (with the Q60 backup); every read must be identical and the backfilled table must hold the
 * same rows as before. Complements backend-dev's migration test (target=16 with handcrafted old data).
 *
 * <p>Data (fictional ticker ZZQ / ZZR): 甲 (id 1) = FEAT-004 scenario A on ZZQ 2026-12-18 200 P (deposit 80,000.00;
 * L1 STO 2 698.70 11-03 10:30; L2 STO 1 360.00 11-04 11:00; BTC 2 241.30 11-05 10:00 → L1, +457.40) plus a Q28 rounded
 * put ZZR 2026-12-18 12.345 P multiplier 1 (STO 1, STO 2, BTC 3 over both lots); 乙 (id 2): deposit 5,000.00, BTO 1 +
 * BTO 1 ZZQ 2026-12-18 9 C, STC 2 over both, BTO 1 again, STC 1.
 */
@DisplayName("FEAT-051 AC8 已有数据升级后不变（V17 / V18 回填）")
class Feat051CloseLotsMigrationSystemTest {

    @TempDir
    Path dir;

    private final ObjectMapper json = new ObjectMapper();

    static final class ExcludeTestConfigurations extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            var meta = reader.getAnnotationMetadata();
            return meta.hasAnnotation(TestConfiguration.class.getName())
                    || meta.hasAnnotation(TestComponent.class.getName());
        }

        @Override
        public boolean equals(Object o) {
            return o != null && o.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(OptionMgrApplication.class)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("qaExcludeTestConfigurations",
                        new ExcludeTestConfigurations()))
                .run("--spring.datasource.url=" + url, "--server.port=0", "--spring.profiles.active=test");
    }

    private String fileUrl(String name) {
        return "jdbc:h2:file:" + dir.resolve(name).toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    private static void exec(String url, String... sql) throws Exception {
        try (Connection con = DriverManager.getConnection(url, "sa", ""); Statement st = con.createStatement()) {
            for (String s : sql) {
                st.execute(s);
            }
        }
    }

    private static List<String> rows(String url, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection con = DriverManager.getConnection(url, "sa", "");
             Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    b.append(i == 1 ? "" : " ").append(rs.getString(i));
                }
                out.add(b.toString());
            }
        }
        return out;
    }

    private List<String> backups(String name) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(f -> f.matches(java.util.regex.Pattern.quote(name) + "-backup-before-V\\d+-.*")).toList();
        }
    }

    private JsonNode call(MockMvc mvc, MockHttpServletRequestBuilder req, Long account, int status) throws Exception {
        if (account != null) {
            req.header("X-Account-Id", account);
        }
        MvcResult r = mvc.perform(req).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        return body.isEmpty() ? null : json.readTree(body);
    }

    private static MockMvc mvc(ConfigurableApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) ctx).build();
    }

    private JsonNode postJson(MockMvc m, String path, String body, long account) throws Exception {
        return call(m, post(path).contentType(MediaType.APPLICATION_JSON).content(body), account, 201);
    }

    private static String open(String action, int contracts, String symbol, String strike, String type, String net,
                               String at, int multiplier) {
        return "{\"symbol\":\"" + symbol + "\",\"expirationDate\":\"2026-12-18\",\"strike\":\"" + strike
                + "\",\"optionType\":\"" + type + "\",\"action\":\"" + action + "\",\"contracts\":" + contracts
                + ",\"netAmount\":\"" + net + "\",\"tradedAtLocal\":\"" + at + "\",\"multiplier\":" + multiplier
                + ",\"note\":null}";
    }

    /** FIFO-mode close (no lotTradeIds) — what the old version accepted. */
    private static String close(String action, int contracts, String symbol, String strike, String type, String net,
                                String at) {
        return "{\"symbol\":\"" + symbol + "\",\"expirationDate\":\"2026-12-18\",\"strike\":\"" + strike
                + "\",\"optionType\":\"" + type + "\",\"action\":\"" + action + "\",\"contracts\":" + contracts
                + ",\"netAmount\":\"" + net + "\",\"tradedAtLocal\":\"" + at + "\",\"note\":null}";
    }

    private static String deposit(String amount, String at) {
        return "{\"type\":\"DEPOSIT\",\"amount\":\"" + amount + "\",\"occurredAtLocal\":\"" + at + "\",\"remark\":null}";
    }

    /** Every read that shows closed lots / P&L / cash / positions, for both accounts and ALL, plus each trade by id. */
    private Map<String, JsonNode> reads(MockMvc m, List<Long> tradeIds) throws Exception {
        Map<String, JsonNode> w = new LinkedHashMap<>();
        for (long acc : List.of(1L, 2L)) {
            for (String p : List.of("/api/cash/summary", "/api/option-positions", "/api/option-trades?size=100",
                    "/api/closed-trades?size=100", "/api/cash/ledger?size=100", "/api/pnl-summary",
                    "/api/audit-log?size=100")) {
                w.put(acc + " " + p, call(m, get(p), acc, 200));
            }
        }
        for (long id : tradeIds) {
            long acc = id <= 6 ? 1L : 2L;
            w.put("trade " + id, call(m, get("/api/option-trades/" + id), acc, 200));
        }
        return w;
    }

    @Test
    @DisplayName("AC8 旧数据（场景 A + BTC 2 张 241.30、Q28 取整的多批次平仓、乙的多头 STC）升级后：被平批次仍为 L1、+457.40，80,817.40/20,000.00/60,817.40，持仓剩 L2，所有读接口与 id 完全一致；回填的 option_close_lot 与升级前相同")
    void existingClosesUnchangedAfterBackfill() throws Exception {
        String url = fileUrl("feat051db");
        List<Long> tradeIds = new ArrayList<>();
        Map<String, JsonNode> before;
        List<String> lotsBefore;
        try (ConfigurableApplicationContext c = start(url)) {
            MockMvc m = mvc(c);
            call(m, post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"乙\"}"), null,
                    201);
            postJson(m, "/api/cash/transfers", deposit("80000.00", "2026-10-26T10:00"), 1);
            long l1 = postJson(m, "/api/option-trades",
                    open("STO", 2, "ZZQ", "200", "PUT", "698.70", "2026-11-03T10:30", 100), 1).get("id").asLong();
            long l2 = postJson(m, "/api/option-trades",
                    open("STO", 1, "ZZQ", "200", "PUT", "360.00", "2026-11-04T11:00", 100), 1).get("id").asLong();
            JsonNode btc = postJson(m, "/api/option-trades/closes",
                    close("BTC", 2, "ZZQ", "200", "PUT", "241.30", "2026-11-05T10:00"), 1);
            assertThat(btc.get("realizedPnl").asText()).isEqualTo("457.40");
            long z1 = postJson(m, "/api/option-trades",
                    open("STO", 1, "ZZR", "12.345", "PUT", "0.50", "2026-11-03T10:00", 1), 1).get("id").asLong();
            long z2 = postJson(m, "/api/option-trades",
                    open("STO", 2, "ZZR", "12.345", "PUT", "0.50", "2026-11-04T10:00", 1), 1).get("id").asLong();
            JsonNode zc = postJson(m, "/api/option-trades/closes",
                    close("BTC", 3, "ZZR", "12.345", "PUT", "0.40", "2026-11-06T10:00"), 1);
            assertThat(zc.get("frozenChange").asText()).as("Q28: 12.35 + 24.69").isEqualTo("-37.04");
            tradeIds.addAll(List.of(l1, l2, btc.get("id").asLong(), z1, z2, zc.get("id").asLong()));

            postJson(m, "/api/cash/transfers", deposit("5000.00", "2026-10-26T10:00"), 2);
            for (String[] t : new String[][] {
                    {"BTO", "1", "50.00", "2026-11-03T10:00"}, {"BTO", "1", "60.00", "2026-11-04T10:00"},
                    {"STC", "2", "130.00", "2026-11-05T10:00"}, {"BTO", "1", "40.00", "2026-11-06T10:00"},
                    {"STC", "1", "45.00", "2026-11-07T10:00"}}) {
                String body = t[0].equals("BTO")
                        ? open("BTO", Integer.parseInt(t[1]), "ZZQ", "9", "CALL", t[2], t[3], 100)
                        : close("STC", Integer.parseInt(t[1]), "ZZQ", "9", "CALL", t[2], t[3]);
                String path = t[0].equals("BTO") ? "/api/option-trades" : "/api/option-trades/closes";
                tradeIds.add(postJson(m, path, body, 2).get("id").asLong());
            }
            assertThat(tradeIds).as("ids 1–6 in 甲, 7–11 in 乙 (reads() relies on it)")
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);

            JsonNode s = call(m, get("/api/cash/summary"), 1L, 200);
            assertThat(List.of(s.get("totalCash").asText(), s.get("frozenCash").asText(),
                    s.get("availableCash").asText())).containsExactly("80818.00", "20000.00", "60818.00");
            before = reads(m, tradeIds);
        }
        lotsBefore = rows(url, "SELECT close_trade_id, lot_trade_id, account_id FROM option_close_lot "
                + "ORDER BY close_trade_id, lot_trade_id");
        assertThat(lotsBefore).containsExactly(
                "3 1 1",
                "6 4 1", "6 5 1",
                "9 7 2", "9 8 2",
                "11 10 2");
        assertThat(rows(url, "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE CAST(version AS INT) > 18"))
                .as("V18 is the latest migration (adapt this test if a later one is added)").containsExactly("0");

        // back to a V16 database with the same business rows
        exec(url, "DROP TABLE option_close_lot",
                "DELETE FROM \"flyway_schema_history\" WHERE version IN ('17', '18')");
        List<String> backupsBefore = backups("feat051db");

        try (ConfigurableApplicationContext c = start(url)) {
            MockMvc m = mvc(c);
            Map<String, JsonNode> after = reads(m, tradeIds);
            for (Map.Entry<String, JsonNode> e : before.entrySet()) {
                assertThat(after.get(e.getKey())).as("unchanged after V17/V18: %s", e.getKey()).isEqualTo(e.getValue());
            }
            JsonNode btc = after.get("trade 3");
            assertThat(btc.get("coveredLots")).hasSize(1);
            assertThat(btc.get("coveredLots").get(0).get("tradeId").asLong()).isEqualTo(1);
            assertThat(btc.get("realizedPnl").asText()).isEqualTo("457.40");
            JsonNode pos = after.get("1 /api/option-positions");
            JsonNode row = null;
            for (JsonNode p : pos.get("positions")) {
                if (p.get("contractLabel").asText().equals("ZZQ 2026-12-18 200 P")) {
                    row = p;
                }
            }
            assertThat(row).isNotNull();
            assertThat(row.get("contracts").asInt()).isEqualTo(1);
            assertThat(row.get("lots").get(0).get("tradeId").asLong()).isEqualTo(2);
            assertThat(row.get("lots").get(0).get("closeDefault").asBoolean()).isTrue();
        }
        assertThat(rows(url, "SELECT close_trade_id, lot_trade_id, account_id FROM option_close_lot "
                + "ORDER BY close_trade_id, lot_trade_id")).as("backfill = what the application stored")
                .isEqualTo(lotsBefore);
        assertThat(backups("feat051db")).as("Q60 backup before the pending migrations").hasSize(backupsBefore.size() + 1);
        assertThat(rows(url, "SELECT MAX(CAST(version AS INT)) FROM \"flyway_schema_history\" WHERE success"))
                .containsExactly("18");
    }
}
