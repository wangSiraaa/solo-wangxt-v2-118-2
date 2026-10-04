package com.treasury.clearing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.clearing.domain.Claim;
import com.treasury.clearing.domain.ClaimStatus;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.repo.ClaimRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance for the saved-batch comparison. Uses its own agreement
 * NA-MULTI (one CNY pocket + one USD pocket) so the shared demo dataset is
 * never touched and test-class order does not matter:
 * <ol>
 *   <li>two identical trials of the same agreement compare to zero difference;</li>
 *   <li>a newly added invoice explains the payment-leg and net-position deltas;</li>
 *   <li>(agreement, currency) pockets never merge, amounts never add across
 *       currencies, and comparing leaves the original batches untouched.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BatchCompareTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ClaimRepository claimRepository;
    @Autowired
    private NettingAgreementRepository agreementRepository;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void seedCompareAgreement() {
        if (agreementRepository.findById("NA-MULTI").isEmpty()) {
            // Netting allowed, cross-currency forbidden -> one pocket per currency.
            NettingAgreement ag = new NettingAgreement("NA-MULTI", "多币种对照测试协议",
                    true, false, null, null,
                    LocalDate.of(2026, 1, 1), null, null, null);
            ag.addMember("A");
            ag.addMember("B");
            ag.addMember("C");
            ag.addMember("E");
            ag.addCurrency("CNY");
            ag.addCurrency("USD");
            agreementRepository.save(ag);
        }
        // CNY ring: A->B 1000, B->C 600, C->A 400 -> 2 cash legs (A->B 400, A->C 200).
        saveClaim("CLM-CMP-001", "INV-MA-101", "A", "B", "1000.00", "CNY");
        saveClaim("CLM-CMP-002", "INV-MB-201", "B", "C", "600.00", "CNY");
        saveClaim("CLM-CMP-003", "INV-MC-301", "C", "A", "400.00", "CNY");
        // USD pocket: A->E 100, E->A 30 -> 1 cash leg (A->E 70 USD).
        saveClaim("CLM-CMP-004", "INV-MA-102", "A", "E", "100.00", "USD");
        saveClaim("CLM-CMP-005", "INV-ME-501", "E", "A", "30.00", "USD");
    }

    private void saveClaim(String id, String invoiceNo, String debtor, String creditor,
                           String amount, String ccy) {
        if (claimRepository.existsById(id)) {
            return;
        }
        claimRepository.save(new Claim(id, invoiceNo, "NA-MULTI", debtor, creditor,
                new BigDecimal(amount), ccy, ClaimStatus.OPEN,
                LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 15), "对照测试债权"));
    }

    // ------------------------------------------------------------------
    // Acceptance: two identical batches show zero difference
    // ------------------------------------------------------------------
    @Test
    @Order(1)
    void identicalBatchesCompareToZeroDiff() throws Exception {
        String b1 = simulate(List.of("NA-MULTI"));
        String b2 = simulate(List.of("NA-MULTI"));

        JsonNode cmp = compare(b1, b2);
        assertThat(cmp.get("left").get("id").asText()).isEqualTo(b1);
        assertThat(cmp.get("right").get("id").asText()).isEqualTo(b2);
        // 2 CNY cash legs + 1 USD cash leg on each side.
        assertThat(cmp.get("leftCashLegCount").asInt()).isEqualTo(3);
        assertThat(cmp.get("rightCashLegCount").asInt()).isEqualTo(3);

        JsonNode groups = cmp.get("groups");
        assertThat(groups).hasSize(2);
        for (JsonNode g : groups) {
            assertThat(g.get("presence").asText()).isEqualTo("BOTH");
            assertThat(g.get("zeroDiff").asBoolean()).isTrue();
            assertThat(g.get("paymentLegs")).isEmpty();
            JsonNode inv = g.get("invoices");
            assertThat(inv.get("includedLeftOnly")).isEmpty();
            assertThat(inv.get("includedRightOnly")).isEmpty();
            assertThat(inv.get("excludedLeftOnly")).isEmpty();
            assertThat(inv.get("excludedRightOnly")).isEmpty();
            assertThat(inv.get("exclusionReasonChanged")).isEmpty();
            for (JsonNode p : g.get("positions")) {
                assertThat(p.get("delta").decimalValue()).isEqualByComparingTo("0.00");
            }
        }
        // Net positions are still listed side by side (all deltas zero).
        JsonNode cny = group(cmp, "NA-MULTI", "CNY");
        assertPosition(cny, "A", "600.00", "600.00");
        assertPosition(cny, "B", "-400.00", "-400.00");
        assertPosition(cny, "C", "-200.00", "-200.00");
        assertThat(cny.get("invoices").get("includedBothCount").asInt()).isEqualTo(3);

        // A batch compared with itself is trivially zero-diff too.
        JsonNode self = compare(b1, b1);
        for (JsonNode g : self.get("groups")) {
            assertThat(g.get("zeroDiff").asBoolean()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Acceptance: invoice diff and payment-leg changes correspond
    // ------------------------------------------------------------------
    @Test
    @Order(2)
    void newInvoiceExplainsLegAndPositionDeltas() throws Exception {
        String before = simulate(List.of("NA-MULTI"));

        // One more CNY debt C->A 600 enters the same agreement between the two trials.
        claimRepository.save(new Claim("CLM-CMP-NEW", "INV-MC-302", "NA-MULTI", "C", "A",
                new BigDecimal("600.00"), "CNY", ClaimStatus.OPEN,
                LocalDate.of(2026, 9, 19), LocalDate.of(2026, 10, 19), "丙追加欠甲货款"));
        String after = simulate(List.of("NA-MULTI"));

        JsonNode cmp = compare(before, after);
        JsonNode cny = group(cmp, "NA-MULTI", "CNY");
        assertThat(cny.get("zeroDiff").asBoolean()).isFalse();
        // The ring now closes further: 2 cash legs collapse to a single one.
        assertThat(cny.get("leftCashLegCount").asInt()).isEqualTo(2);
        assertThat(cny.get("rightCashLegCount").asInt()).isEqualTo(1);

        // Invoice diff: the new claim is included on the right only.
        JsonNode inv = cny.get("invoices");
        assertThat(inv.get("includedLeftOnly")).isEmpty();
        assertThat(inv.get("includedBothCount").asInt()).isEqualTo(3);
        JsonNode added = inv.get("includedRightOnly");
        assertThat(added).hasSize(1);
        assertThat(added.get(0).get("claimId").asText()).isEqualTo("CLM-CMP-NEW");
        assertThat(added.get(0).get("invoiceNo").asText()).isEqualTo("INV-MC-302");
        assertThat(added.get(0).get("debtorCode").asText()).isEqualTo("C");
        assertThat(added.get(0).get("creditorCode").asText()).isEqualTo("A");
        assertThat(added.get(0).get("bookedAmount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(added.get(0).get("originalCurrency").asText()).isEqualTo("CNY");

        // Payment-leg deltas line up with that invoice: A's two payments vanish,
        // a single C->B 400 payment appears. Each row carries leg ids for drill-back.
        JsonNode legs = cny.get("paymentLegs");
        assertThat(legs).hasSize(3);
        assertLeg(legs, "A", "B", "400.00", "0.00");
        assertLeg(legs, "A", "C", "200.00", "0.00");
        assertLeg(legs, "C", "B", "0.00", "400.00");

        // Net positions move exactly by the new claim: A 600->0, C -200->+400, B steady.
        assertPosition(cny, "A", "600.00", "0.00");
        assertPosition(cny, "B", "-400.00", "-400.00");
        assertPosition(cny, "C", "-200.00", "400.00");

        // The USD pocket's included invoices, positions and payment legs are
        // untouched; only its exclusion list notes the new CNY invoice
        // (cross-currency set-off is forbidden, one pocket per currency).
        JsonNode usd = group(cmp, "NA-MULTI", "USD");
        assertThat(usd.get("paymentLegs")).isEmpty();
        assertThat(usd.get("invoices").get("includedLeftOnly")).isEmpty();
        assertThat(usd.get("invoices").get("includedRightOnly")).isEmpty();
        assertThat(usd.get("invoices").get("excludedLeftOnly")).isEmpty();
        JsonNode usdExcluded = usd.get("invoices").get("excludedRightOnly");
        assertThat(usdExcluded).hasSize(1);
        assertThat(usdExcluded.get(0).get("claimId").asText()).isEqualTo("CLM-CMP-NEW");
        assertThat(usdExcluded.get(0).get("reasonCode").asText()).isEqualTo("CROSS_CCY_NOT_ALLOWED");
        for (JsonNode p : usd.get("positions")) {
            assertThat(p.get("delta").decimalValue()).isEqualByComparingTo("0.00");
        }

        // Batch-level leg counts drop by one; amounts stay inside their pockets.
        assertThat(cmp.get("leftCashLegCount").asInt()).isEqualTo(3);
        assertThat(cmp.get("rightCashLegCount").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Acceptance: pockets never merge; comparing is read-only
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    void pocketsStaySeparateAndCompareIsReadOnly() throws Exception {
        String multiOnly = simulate(List.of("NA-MULTI"));
        String all = simulate(List.of()); // every active agreement

        String batchJsonBefore = batchJson(multiOnly);
        JsonNode cmp = compare(multiOnly, all);

        // Every (agreement, currency) pocket is its own row — same agreement with
        // two currencies yields two rows; agreements absent on the left are
        // listed as RIGHT_ONLY, never merged into the CNY rows.
        assertThat(cmp.get("groups")).hasSize(5);
        assertThat(group(cmp, "NA-MULTI", "CNY").get("presence").asText()).isEqualTo("BOTH");
        assertThat(group(cmp, "NA-MULTI", "USD").get("presence").asText()).isEqualTo("BOTH");
        assertThat(group(cmp, "NA-CNY", "CNY").get("presence").asText()).isEqualTo("RIGHT_ONLY");
        assertThat(group(cmp, "NA-NOFF", "CNY").get("presence").asText()).isEqualTo("RIGHT_ONLY");
        assertThat(group(cmp, "NA-XCCY", "CNY").get("presence").asText()).isEqualTo("RIGHT_ONLY");

        // The two NA-MULTI pockets share the agreement but keep separate currencies.
        assertThat(group(cmp, "NA-MULTI", "CNY").get("settlementCurrency").asText()).isEqualTo("CNY");
        assertThat(group(cmp, "NA-MULTI", "USD").get("settlementCurrency").asText()).isEqualTo("USD");
        // USD pocket: 70 USD net at A; CNY pocket: 400 CNY net at C (A nets to zero
        // after the order-2 test added C->A 600) — never one summed figure.
        assertPosition(group(cmp, "NA-MULTI", "USD"), "A", "70.00", "70.00");
        assertPosition(group(cmp, "NA-MULTI", "USD"), "E", "-70.00", "-70.00");
        assertPosition(group(cmp, "NA-MULTI", "CNY"), "B", "-400.00", "-400.00");
        assertPosition(group(cmp, "NA-MULTI", "CNY"), "C", "400.00", "400.00");

        // Read-only: the compared batch is byte-for-byte unchanged afterwards.
        assertThat(batchJson(multiOnly)).isEqualTo(batchJsonBefore);
        JsonNode stillSimulated = json.readTree(batchJson(multiOnly));
        assertThat(stillSimulated.get("status").asText()).isEqualTo("SIMULATED");

        // Unknown batch id -> 404.
        mockMvc.perform(get("/api/batches/compare").param("left", "NOPE").param("right", multiOnly))
                .andExpect(status().isNotFound());
    }

    // ---------------- helpers ----------------

    private String simulate(List<String> agreementCodes) throws Exception {
        var req = json.createObjectNode().put("valuationDate", "2026-09-30");
        var codes = req.putArray("agreementCodes");
        agreementCodes.forEach(codes::add);
        JsonNode batch = json.readTree(mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return batch.get("id").asText();
    }

    private JsonNode compare(String left, String right) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/batches/compare")
                        .param("left", left).param("right", right))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String batchJson(String id) throws Exception {
        return mockMvc.perform(get("/api/batches/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode group(JsonNode cmp, String agreementCode, String ccy) {
        for (JsonNode g : cmp.get("groups")) {
            if (g.get("agreementCode").asText().equals(agreementCode)
                    && g.get("settlementCurrency").asText().equals(ccy)) {
                return g;
            }
        }
        throw new AssertionError("group missing: " + agreementCode + "/" + ccy);
    }

    private void assertLeg(JsonNode legs, String payer, String receiver,
                           String leftAmount, String rightAmount) {
        for (JsonNode l : legs) {
            if (l.get("payerCode").asText().equals(payer)
                    && l.get("receiverCode").asText().equals(receiver)) {
                assertThat(l.get("leftAmount").decimalValue()).isEqualByComparingTo(leftAmount);
                assertThat(l.get("rightAmount").decimalValue()).isEqualByComparingTo(rightAmount);
                BigDecimal delta = l.get("delta").decimalValue();
                assertThat(delta).isEqualByComparingTo(
                        new BigDecimal(rightAmount).subtract(new BigDecimal(leftAmount)));
                // Drill-back references: present exactly on the side that has the leg.
                assertThat(l.get("leftLegId").asText().isEmpty())
                        .isEqualTo(new BigDecimal(leftAmount).signum() == 0);
                assertThat(l.get("rightLegId").asText().isEmpty())
                        .isEqualTo(new BigDecimal(rightAmount).signum() == 0);
                return;
            }
        }
        throw new AssertionError("leg diff missing: " + payer + "->" + receiver);
    }

    private void assertPosition(JsonNode group, String entity, String left, String right) {
        for (JsonNode p : group.get("positions")) {
            if (p.get("entityCode").asText().equals(entity)) {
                assertThat(p.get("leftPosition").decimalValue()).isEqualByComparingTo(left);
                assertThat(p.get("rightPosition").decimalValue()).isEqualByComparingTo(right);
                assertThat(p.get("delta").decimalValue()).isEqualByComparingTo(
                        new BigDecimal(right).subtract(new BigDecimal(left)));
                return;
            }
        }
        throw new AssertionError("position missing for entity " + entity);
    }
}
