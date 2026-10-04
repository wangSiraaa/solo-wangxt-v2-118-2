package com.treasury.clearing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.clearing.domain.Claim;
import com.treasury.clearing.domain.ClaimStatus;
import com.treasury.clearing.repo.ClaimRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance of the read-only batch comparison:
 * - identical batches (or two deterministic runs over the same claims) show zero difference;
 * - invoice inclusion/exclusion diffs and positive-leg diffs of one agreement correspond;
 * - groups of different agreements/currencies are reported separately, never summed;
 * - comparing never rewrites the batches, the claims or any status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BatchCompareTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ClaimRepository claimRepository;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void identicalBatchesHaveZeroDifference() throws Exception {
        String b1 = simulate(null);

        JsonNode cmp = compare(b1, b1);

        assertThat(cmp.get("zeroDifference").asBoolean()).isTrue();
        // Same currency (CNY) but different agreements: three separate groups.
        assertThat(groupKeys(cmp)).containsExactly("NA-CNY|CNY", "NA-NOFF|CNY", "NA-XCCY|CNY");
        for (JsonNode g : cmp.get("groups")) {
            assertThat(g.get("presence").asText()).isEqualTo("BOTH");
            assertThat(g.get("zeroDifference").asBoolean()).isTrue();
            assertThat(g.get("invoiceChangeCount").asInt()).isZero();
            assertThat(g.get("legChangeCount").asInt()).isZero();
            for (JsonNode inv : g.get("invoices")) {
                assertThat(inv.get("changed").asBoolean()).isFalse();
            }
            for (JsonNode p : g.get("positions")) {
                assertThat(p.get("diff").decimalValue()).isEqualByComparingTo("0.00");
            }
            for (JsonNode l : g.get("legs")) {
                assertThat(l.get("diff").decimalValue()).isEqualByComparingTo("0.00");
            }
        }
    }

    @Test
    void invoiceAndLegChangesCorrespondBetweenTwoRuns() throws Exception {
        String b1 = simulate(null);
        postJson("/api/batches/" + b1 + "/confirm"); // settles NA-CNY + NA-XCCY claims

        // A new debt appears after the first trial run.
        claimRepository.save(new Claim("CLM-R3-001", "INV-D-4001", "NA-CNY", "D", "A",
                new BigDecimal("350.00"), "CNY", ClaimStatus.OPEN,
                LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 25), "丁欠甲新增货款"));

        String b2 = simulate(null);
        JsonNode cmp = compare(b1, b2);
        assertThat(cmp.get("zeroDifference").asBoolean()).isFalse();

        // --- NA-CNY group: invoice diffs and payment-leg diffs tell the same story ---
        JsonNode cny = group(cmp, "NA-CNY|CNY");
        assertThat(cny.get("presence").asText()).isEqualTo("BOTH");
        assertThat(cny.get("settlementCurrency").asText()).isEqualTo("CNY");

        JsonNode ring = invoice(cny, "CLM-R1-001");
        assertThat(ring.get("leftState").asText()).isEqualTo("INCLUDED");
        assertThat(ring.get("rightState").asText()).isEqualTo("EXCLUDED");
        assertThat(ring.get("rightReasonCode").asText()).isEqualTo("NOT_OPEN");
        assertThat(ring.get("changed").asBoolean()).isTrue();
        // Full invoice amount, not a leg slice share.
        assertThat(ring.get("amount").decimalValue()).isEqualByComparingTo("1000.00");

        JsonNode pledged = invoice(cny, "CLM-EX-001");
        assertThat(pledged.get("leftState").asText()).isEqualTo("EXCLUDED");
        assertThat(pledged.get("rightState").asText()).isEqualTo("EXCLUDED");
        assertThat(pledged.get("leftReasonCode").asText()).isEqualTo("PLEDGED");
        assertThat(pledged.get("changed").asBoolean()).isFalse();
        // Excluded rows still carry display facts (claim master, read-only fill-in).
        assertThat(pledged.get("amount").decimalValue()).isEqualByComparingTo("300.00");

        JsonNode added = invoice(cny, "CLM-R3-001");
        assertThat(added.get("leftState").asText()).isEqualTo("ABSENT");
        assertThat(added.get("rightState").asText()).isEqualTo("INCLUDED");
        assertThat(added.get("changed").asBoolean()).isTrue();

        // Leg diffs: the two old cash legs vanish, the new D->A leg appears.
        JsonNode ab = leg(cny, "A", "B");
        assertThat(ab.get("presence").asText()).isEqualTo("LEFT_ONLY");
        assertThat(ab.get("leftAmount").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(ab.get("diff").decimalValue()).isEqualByComparingTo("-400.00");
        assertThat(ab.get("leftLegIds").get(0).asText()).startsWith(b1);

        JsonNode da = leg(cny, "D", "A");
        assertThat(da.get("presence").asText()).isEqualTo("RIGHT_ONLY");
        assertThat(da.get("rightAmount").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(da.get("diff").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(da.get("rightLegIds").get(0).asText()).startsWith(b2);

        assertThat(cny.get("leftCashLegCount").asInt()).isEqualTo(2);
        assertThat(cny.get("rightCashLegCount").asInt()).isEqualTo(1);
        assertThat(cny.get("leftNetTotal").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(cny.get("rightNetTotal").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(cny.get("netTotalDiff").decimalValue()).isEqualByComparingTo("-250.00");

        // Net positions: A was a 600 net payer, now a 350 net receiver.
        JsonNode posA = position(cny, "A");
        assertThat(posA.get("leftNet").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(posA.get("rightNet").decimalValue()).isEqualByComparingTo("-350.00");
        assertThat(posA.get("diff").decimalValue()).isEqualByComparingTo("-950.00");

        // NA-NOFF pass-through claims stay open -> that group is untouched.
        JsonNode noff = group(cmp, "NA-NOFF|CNY");
        assertThat(noff.get("zeroDifference").asBoolean()).isTrue();

        // NA-XCCY settles in CNY too, yet is reported as its own group.
        JsonNode xccy = group(cmp, "NA-XCCY|CNY");
        assertThat(xccy.get("presence").asText()).isEqualTo("BOTH");
        assertThat(xccy.get("zeroDifference").asBoolean()).isFalse();
        assertThat(leg(xccy, "A", "E").get("presence").asText()).isEqualTo("LEFT_ONLY");
        JsonNode eurInvoice = invoice(xccy, "CLM-XC-004");
        assertThat(eurInvoice.get("leftReasonCode").asText()).isEqualTo("CCY_NOT_ALLOWED");
        assertThat(eurInvoice.get("changed").asBoolean()).isFalse();
    }

    @Test
    void compareIsReadOnlyAndIndependentRunsMatch() throws Exception {
        String b1 = simulate(null);
        String b2 = simulate(null); // same claims, deterministic engine

        JsonNode before1 = getBatch(b1);
        JsonNode before2 = getBatch(b2);
        JsonNode claimsBefore = claims();

        JsonNode cmp = compare(b1, b2);
        assertThat(cmp.get("zeroDifference").asBoolean()).isTrue();
        for (JsonNode g : cmp.get("groups")) {
            assertThat(g.get("zeroDifference").asBoolean()).isTrue();
        }

        // Comparing must not rewrite the saved batches, claims or any status.
        assertThat(getBatch(b1)).isEqualTo(before1);
        assertThat(getBatch(b2)).isEqualTo(before2);
        assertThat(claims()).isEqualTo(claimsBefore);
        assertThat(getBatch(b1).get("status").asText()).isEqualTo("SIMULATED");
        assertThat(getBatch(b2).get("status").asText()).isEqualTo("SIMULATED");
    }

    @Test
    void groupsPresentOnOneSideOnlyAreReportedSeparately() throws Exception {
        String bAll = simulate(null);
        String bNoff = simulate(List.of("NA-NOFF"));

        JsonNode cmp = compare(bAll, bNoff);
        assertThat(cmp.get("zeroDifference").asBoolean()).isFalse();

        JsonNode cny = group(cmp, "NA-CNY|CNY");
        assertThat(cny.get("presence").asText()).isEqualTo("LEFT_ONLY");
        assertThat(cny.get("leftGroupId").asText()).startsWith(bAll);
        assertThat(cny.get("rightGroupId").isNull()).isTrue();
        assertThat(cny.get("invoiceChangeCount").asInt()).isEqualTo(8);
        assertThat(cny.get("legChangeCount").asInt()).isEqualTo(2);
        // Every invoice of a left-only group is "present -> absent".
        for (JsonNode inv : cny.get("invoices")) {
            assertThat(inv.get("rightState").asText()).isEqualTo("ABSENT");
            assertThat(inv.get("changed").asBoolean()).isTrue();
        }

        JsonNode noff = group(cmp, "NA-NOFF|CNY");
        assertThat(noff.get("presence").asText()).isEqualTo("BOTH");
        assertThat(noff.get("zeroDifference").asBoolean()).isTrue();

        // Mirrored comparison flips the side flags.
        JsonNode flipped = compare(bNoff, bAll);
        assertThat(group(flipped, "NA-CNY|CNY").get("presence").asText()).isEqualTo("RIGHT_ONLY");
        assertThat(group(flipped, "NA-XCCY|CNY").get("presence").asText()).isEqualTo("RIGHT_ONLY");
        assertThat(group(flipped, "NA-NOFF|CNY").get("presence").asText()).isEqualTo("BOTH");
    }

    @Test
    void unknownBatchYields404() throws Exception {
        String b1 = simulate(null);
        mockMvc.perform(get("/api/batches/compare").param("left", "NOPE").param("right", b1))
                .andExpect(status().isNotFound());
    }

    // ---------------- helpers ----------------

    private String simulate(List<String> agreementCodes) throws Exception {
        var req = json.createObjectNode().put("valuationDate", "2026-09-30");
        if (agreementCodes != null) {
            var arr = req.putArray("agreementCodes");
            agreementCodes.forEach(arr::add);
        }
        JsonNode res = json.readTree(mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return res.get("id").asText();
    }

    private JsonNode compare(String left, String right) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/batches/compare")
                        .param("left", left).param("right", right))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String path) throws Exception {
        return json.readTree(mockMvc.perform(post(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode getBatch(String id) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/batches/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode claims() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/claims"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<String> groupKeys(JsonNode cmp) {
        List<String> keys = new ArrayList<>();
        cmp.get("groups").forEach(g -> keys.add(g.get("key").asText()));
        return keys;
    }

    private JsonNode group(JsonNode cmp, String key) {
        for (JsonNode g : cmp.get("groups")) {
            if (g.get("key").asText().equals(key)) {
                return g;
            }
        }
        throw new AssertionError("group missing: " + key);
    }

    private JsonNode invoice(JsonNode g, String claimId) {
        for (JsonNode inv : g.get("invoices")) {
            if (inv.get("claimId").asText().equals(claimId)) {
                return inv;
            }
        }
        throw new AssertionError("invoice diff missing: " + claimId);
    }

    private JsonNode leg(JsonNode g, String payer, String receiver) {
        for (JsonNode l : g.get("legs")) {
            if (l.get("payerCode").asText().equals(payer)
                    && l.get("receiverCode").asText().equals(receiver)) {
                return l;
            }
        }
        throw new AssertionError("leg diff missing: " + payer + "->" + receiver);
    }

    private JsonNode position(JsonNode g, String entity) {
        for (JsonNode p : g.get("positions")) {
            if (p.get("entityCode").asText().equals(entity)) {
                return p;
            }
        }
        throw new AssertionError("position missing: " + entity);
    }
}
