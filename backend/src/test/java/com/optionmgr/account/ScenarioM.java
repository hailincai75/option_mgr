package com.optionmgr.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionmgr.account.web.AccountRef;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FEAT-033 scenario M (the M1 data of the requirement) entered through the API into one account, plus a snapshot
 * of every read endpoint. "Now" must be 2026-11-12 10:00 New York.
 */
final class ScenarioM {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final long accountId;

    ScenarioM(MockMvc mvc, ObjectMapper json, long accountId) {
        this.mvc = mvc;
        this.json = json;
        this.accountId = accountId;
    }

    JsonNode call(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req.header(AccountRef.HEADER, String.valueOf(accountId))).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (r.getResponse().getStatus() != expectedStatus) {
            throw new AssertionError("expected " + expectedStatus + " but was " + r.getResponse().getStatus() + ": "
                    + raw);
        }
        return raw.isEmpty() ? null : json.readTree(raw);
    }

    JsonNode send(MockHttpServletRequestBuilder req, ObjectNode body, int expectedStatus) throws Exception {
        return call(req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)),
                expectedStatus);
    }

    JsonNode get(String url) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URI.create(url)), 200);
    }

    long transfer(String type, String amount, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("type", type);
        n.put("amount", amount);
        n.put("occurredAtLocal", nyTime);
        return send(post("/api/cash/transfers"), n, 201).get("id").asLong();
    }

    long trade(String action, String symbol, String exp, String strike, String type, int contracts,
               Integer multiplier, String net, String nyTime) throws Exception {
        ObjectNode n = json.createObjectNode();
        n.put("symbol", symbol);
        n.put("expirationDate", exp);
        n.put("strike", strike);
        n.put("optionType", type);
        n.put("action", action);
        n.put("contracts", contracts);
        if (multiplier != null) {
            n.put("multiplier", multiplier);
        }
        n.put("netAmount", net);
        n.put("tradedAtLocal", nyTime);
        String url = action.endsWith("C") ? "/api/option-trades/closes" : "/api/option-trades";
        return send(post(url), n, 201).get("id").asLong();
    }

    /** Enters scenario M (deposit #1, trades #1-#16 in order, withdrawal #2, two edits). */
    void build() throws Exception {
        transfer("DEPOSIT", "100000.00", "2026-10-26T10:00");
        trade("BTO", "TSLA", "2026-12-18", "300", "CALL", 1, 100, "500.00", "2026-10-27T10:00");
        trade("STO", "AAPL", "2026-11-20", "180", "PUT", 1, 100, "150.00", "2026-10-28T10:00");
        trade("STC", "TSLA", "2026-12-18", "300", "CALL", 1, null, "620.00", "2026-10-30T10:00");
        trade("BTC", "AAPL", "2026-11-20", "180", "PUT", 1, null, "200.00", "2026-10-31T23:30");
        trade("STO", "AAPL", "2026-12-18", "200", "PUT", 2, 100, "698.70", "2026-11-02T10:30");
        trade("BTO", "MSFT", "2027-01-15", "450", "CALL", 1, 100, "900.00", "2026-11-02T14:00");
        trade("STO", "XYZ", "2026-12-18", "200", "PUT", 1, 10, "34.35", "2026-11-02T15:00");
        trade("BTO", "AAPL", "2026-12-18", "210", "CALL", 2, 100, "1000.00", "2026-11-03T09:45");
        trade("STO", "AAPL", "2026-12-18", "200", "PUT", 1, 100, "360.00", "2026-11-03T11:00");
        trade("BTC", "AAPL", "2026-12-18", "200", "PUT", 3, null, "400.00", "2026-11-05T10:00");
        trade("BTC", "XYZ", "2026-12-18", "200", "PUT", 1, null, "50.00", "2026-11-06T10:00");
        trade("STC", "MSFT", "2027-01-15", "450", "CALL", 1, null, "1250.00", "2026-11-06T23:30");
        long c4 = trade("STC", "AAPL", "2026-12-18", "210", "CALL", 2, null, "640.00", "2026-11-07T10:00");
        trade("STO", "TSLA", "2026-11-06", "250", "PUT", 1, 100, "300.00", "2026-11-02T09:00");
        trade("BTO", "SPY", "2026-11-13", "600", "CALL", 1, 100, "80.00", "2026-11-09T10:00");
        trade("STO", "AAPL", "2026-11-20", "190", "PUT", 1, 100, "120.00", "2026-11-09T10:00");
        long w2 = transfer("WITHDRAWAL", "1000.00", "2026-11-10T10:00");

        ObjectNode edit = json.createObjectNode();
        edit.put("contracts", 2);
        edit.put("netAmount", "700.00");
        edit.put("tradedAtLocal", "2026-11-07T10:00");
        send(put("/api/option-trades/" + c4), edit, 200);

        ObjectNode w = json.createObjectNode();
        w.put("type", "WITHDRAWAL");
        w.put("amount", "1500.00");
        w.put("occurredAtLocal", "2026-11-10T10:00");
        send(put("/api/cash/transfers/" + w2), w, 200);
    }

    /** Every read endpoint of the account (all items on one page). */
    Map<String, JsonNode> snapshot() throws Exception {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String url : new String[] {"/api/cash/summary", "/api/cash/ledger?size=100", "/api/option-trades?size=100",
                "/api/option-positions", "/api/closed-trades?size=100", "/api/pnl-summary", "/api/dashboard",
                "/api/audit-log?size=100"}) {
            out.put(url, get(url));
        }
        for (JsonNode t : out.get("/api/option-trades?size=100").get("items")) {
            String url = "/api/option-trades/" + t.get("id").asLong();
            out.put(url, get(url));
        }
        return out;
    }
}
