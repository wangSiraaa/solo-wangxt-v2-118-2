package com.treasury.clearing.service.compare;

import com.treasury.clearing.domain.BatchExclusion;
import com.treasury.clearing.domain.BatchGroup;
import com.treasury.clearing.domain.BatchLeg;
import com.treasury.clearing.domain.Claim;
import com.treasury.clearing.domain.LegItem;
import com.treasury.clearing.domain.NettingBatch;
import com.treasury.clearing.repo.ClaimRepository;
import com.treasury.clearing.service.BatchService;
import com.treasury.clearing.service.compare.BatchComparison.GroupComparison;
import com.treasury.clearing.service.compare.BatchComparison.InvoiceDiff;
import com.treasury.clearing.service.compare.BatchComparison.InvoiceState;
import com.treasury.clearing.service.compare.BatchComparison.LegDiff;
import com.treasury.clearing.service.compare.BatchComparison.PositionDiff;
import com.treasury.clearing.service.compare.BatchComparison.Presence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only comparison of two already-saved batches. The comparison only
 * re-reads the persisted {@code batch_*} structures: it never runs the netting
 * planner, never re-estimates an FX rate and never changes any confirmation
 * status. All arithmetic stays inside one (agreement, settlement currency)
 * group, so amounts of different currencies are never added together.
 */
@Service
public class BatchCompareService {

    private static final int MONEY = 2;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private final BatchService batchService;
    private final ClaimRepository claimRepository;

    public BatchCompareService(BatchService batchService, ClaimRepository claimRepository) {
        this.batchService = batchService;
        this.claimRepository = claimRepository;
    }

    @Transactional(readOnly = true)
    public BatchComparison compare(String leftId, String rightId) {
        NettingBatch left = batchService.getBatch(leftId);
        NettingBatch right = batchService.getBatch(rightId);

        Map<String, BatchGroup> leftGroups = groupsByKey(left);
        Map<String, BatchGroup> rightGroups = groupsByKey(right);

        var keys = new TreeSet<String>();
        keys.addAll(leftGroups.keySet());
        keys.addAll(rightGroups.keySet());

        List<GroupComparison> groups = new ArrayList<>();
        boolean zeroDifference = true;
        for (String key : keys) {
            GroupComparison gc = compareGroup(key, leftGroups.get(key), rightGroups.get(key));
            groups.add(gc);
            zeroDifference &= gc.zeroDifference();
        }
        return new BatchComparison(toRef(left), toRef(right), groups, zeroDifference);
    }

    // ---------------- group matching ----------------

    /** One group per (agreement, settlement currency) — the batch's own isolation key. */
    private Map<String, BatchGroup> groupsByKey(NettingBatch batch) {
        Map<String, BatchGroup> out = new LinkedHashMap<>();
        for (BatchGroup g : batch.getGroups()) {
            out.put(groupKey(g.getAgreementCode(), g.getSettlementCurrency()), g);
        }
        return out;
    }

    private static String groupKey(String agreementCode, String settlementCurrency) {
        return agreementCode + "|" + settlementCurrency;
    }

    private GroupComparison compareGroup(String key, BatchGroup left, BatchGroup right) {
        Presence presence = left != null && right != null ? Presence.BOTH
                : left != null ? Presence.LEFT_ONLY : Presence.RIGHT_ONLY;
        BatchGroup any = left != null ? left : right;

        List<InvoiceDiff> invoices = compareInvoices(left, right);
        List<PositionDiff> positions = comparePositions(left, right);
        List<LegDiff> legs = compareLegs(left, right);

        int invoiceChanges = (int) invoices.stream().filter(InvoiceDiff::changed).count();
        int legChanges = (int) legs.stream()
                .filter(l -> l.presence() != Presence.BOTH || l.diff().signum() != 0)
                .count();
        boolean positionsAllEqual = positions.stream().allMatch(p -> p.diff().signum() == 0);

        BigDecimal leftNetTotal = cashTotal(left);
        BigDecimal rightNetTotal = cashTotal(right);

        return new GroupComparison(key, any.getAgreementCode(), any.getAgreementName(),
                any.getSettlementCurrency(), presence,
                left != null ? left.getId() : null,
                right != null ? right.getId() : null,
                invoices, positions, legs,
                invoiceChanges, legChanges,
                cashLegCount(left), cashLegCount(right),
                leftNetTotal, rightNetTotal,
                rightNetTotal.subtract(leftNetTotal).setScale(MONEY, RM),
                presence == Presence.BOTH && invoiceChanges == 0 && legChanges == 0 && positionsAllEqual);
    }

    // ---------------- invoices ----------------

    /** One claim's state inside one group, as persisted in the saved batch. */
    private record SideInvoice(InvoiceState state, String invoiceNo, String reasonCode,
                               String debtorCode, String creditorCode,
                               BigDecimal amount, String currency) {
        boolean hasDisplay() {
            return debtorCode != null;
        }
    }

    private List<InvoiceDiff> compareInvoices(BatchGroup left, BatchGroup right) {
        Map<String, SideInvoice> leftInv = left != null ? invoiceStates(left) : Map.of();
        Map<String, SideInvoice> rightInv = right != null ? invoiceStates(right) : Map.of();

        var claimIds = new TreeSet<String>();
        claimIds.addAll(leftInv.keySet());
        claimIds.addAll(rightInv.keySet());

        List<InvoiceDiff> out = new ArrayList<>();
        for (String claimId : claimIds) {
            SideInvoice l = leftInv.get(claimId);
            SideInvoice r = rightInv.get(claimId);
            SideInvoice display = displaySource(claimId, l, r);

            InvoiceState leftState = l != null ? l.state() : InvoiceState.ABSENT;
            InvoiceState rightState = r != null ? r.state() : InvoiceState.ABSENT;
            String leftReason = l != null ? l.reasonCode() : null;
            String rightReason = r != null ? r.reasonCode() : null;
            boolean changed = leftState != rightState
                    || (leftState == InvoiceState.EXCLUDED && rightState == InvoiceState.EXCLUDED
                        && !Objects.equals(leftReason, rightReason));

            out.add(new InvoiceDiff(claimId, display.invoiceNo(), display.debtorCode(),
                    display.creditorCode(), display.amount(), display.currency(),
                    leftState, rightState, leftReason, rightReason, changed));
        }
        out.sort(Comparator.comparing(InvoiceDiff::invoiceNo,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InvoiceDiff::claimId));
        return out;
    }

    private Map<String, SideInvoice> invoiceStates(BatchGroup group) {
        Map<String, SideInvoice> out = new LinkedHashMap<>();
        for (BatchLeg leg : group.getLegs()) {
            for (LegItem it : leg.getItems()) {
                out.putIfAbsent(it.getClaimId(), new SideInvoice(InvoiceState.INCLUDED,
                        it.getInvoiceNo(), null, it.getDebtorCode(), it.getCreditorCode(),
                        it.getOriginalAmount(), it.getOriginalCurrency()));
            }
        }
        for (BatchExclusion ex : group.getExclusions()) {
            out.putIfAbsent(ex.getClaimId(), new SideInvoice(InvoiceState.EXCLUDED,
                    ex.getInvoiceNo(), ex.getReasonCode(), null, null, null, null));
        }
        return out;
    }

    /**
     * Display facts come from the (read-only) claim master: leg items only carry
     * the slice share of a claim, never the full invoice amount. The claim row's
     * invoice/amount/direction are immutable, so this cannot diverge from what
     * the saved batch saw. Batch rows are the fallback if the claim is gone.
     */
    private SideInvoice displaySource(String claimId, SideInvoice l, SideInvoice r) {
        Claim claim = claimRepository.findById(claimId).orElse(null);
        SideInvoice any = l != null ? l : r;
        String invoiceNo = any != null ? any.invoiceNo() : null;
        if (claim == null) {
            if (any != null && any.hasDisplay()) {
                return any;
            }
            return new SideInvoice(InvoiceState.ABSENT, invoiceNo, null, "", "",
                    BigDecimal.ZERO.setScale(MONEY, RM), "");
        }
        return new SideInvoice(InvoiceState.ABSENT, claim.getInvoiceNo(), null,
                claim.getDebtorCode(), claim.getCreditorCode(),
                claim.getAmount(), claim.getCurrency());
    }

    // ---------------- net positions ----------------

    /**
     * Net position per legal entity from the saved positive legs, in the group's
     * settlement currency: positive = net payer. This mirrors the engine's own
     * invariant (leg flows equal booked positions) without recomputing anything.
     */
    private List<PositionDiff> comparePositions(BatchGroup left, BatchGroup right) {
        Map<String, BigDecimal> leftPos = positionsOf(left);
        Map<String, BigDecimal> rightPos = positionsOf(right);

        var entities = new TreeSet<String>();
        entities.addAll(leftPos.keySet());
        entities.addAll(rightPos.keySet());

        List<PositionDiff> out = new ArrayList<>();
        for (String entity : entities) {
            BigDecimal l = leftPos.getOrDefault(entity, zero());
            BigDecimal r = rightPos.getOrDefault(entity, zero());
            out.add(new PositionDiff(entity, l, r, r.subtract(l).setScale(MONEY, RM)));
        }
        return out;
    }

    private Map<String, BigDecimal> positionsOf(BatchGroup group) {
        Map<String, BigDecimal> pos = new LinkedHashMap<>();
        if (group == null) {
            return pos;
        }
        for (BatchLeg leg : group.getLegs()) {
            if (leg.getAmount().signum() <= 0) {
                continue;
            }
            pos.merge(leg.getPayerCode(), leg.getAmount(), BigDecimal::add);
            pos.merge(leg.getReceiverCode(), leg.getAmount().negate(), BigDecimal::add);
        }
        return pos;
    }

    // ---------------- payment legs ----------------

    /** Cash legs of one payer→receiver pair, summed within the group's own currency. */
    private static final class LegAgg {
        final String payerCode;
        final String receiverCode;
        BigDecimal amount = BigDecimal.ZERO.setScale(MONEY, RM);
        final List<String> legIds = new ArrayList<>();

        LegAgg(String payerCode, String receiverCode) {
            this.payerCode = payerCode;
            this.receiverCode = receiverCode;
        }
    }

    private List<LegDiff> compareLegs(BatchGroup left, BatchGroup right) {
        Map<String, LegAgg> leftLegs = cashLegsOf(left);
        Map<String, LegAgg> rightLegs = cashLegsOf(right);

        var pairs = new TreeSet<String>();
        pairs.addAll(leftLegs.keySet());
        pairs.addAll(rightLegs.keySet());

        List<LegDiff> out = new ArrayList<>();
        for (String pair : pairs) {
            LegAgg l = leftLegs.get(pair);
            LegAgg r = rightLegs.get(pair);
            Presence presence = l != null && r != null ? Presence.BOTH
                    : l != null ? Presence.LEFT_ONLY : Presence.RIGHT_ONLY;
            BigDecimal leftAmount = l != null ? l.amount : null;
            BigDecimal rightAmount = r != null ? r.amount : null;
            BigDecimal diff = (rightAmount != null ? rightAmount : zero())
                    .subtract(leftAmount != null ? leftAmount : zero())
                    .setScale(MONEY, RM);
            LegAgg any = l != null ? l : r;
            out.add(new LegDiff(any.payerCode, any.receiverCode, presence,
                    leftAmount, rightAmount, diff,
                    l != null ? List.copyOf(l.legIds) : List.of(),
                    r != null ? List.copyOf(r.legIds) : List.of(),
                    left != null ? left.getId() : null,
                    right != null ? right.getId() : null));
        }
        return out;
    }

    /** Positive-amount legs only; zero-amount set-off memos are trace records. */
    private Map<String, LegAgg> cashLegsOf(BatchGroup group) {
        Map<String, LegAgg> out = new TreeMap<>();
        if (group == null) {
            return out;
        }
        for (BatchLeg leg : group.getLegs()) {
            if (leg.getAmount().signum() <= 0) {
                continue;
            }
            String pair = leg.getPayerCode() + "→" + leg.getReceiverCode();
            LegAgg agg = out.computeIfAbsent(pair,
                    k -> new LegAgg(leg.getPayerCode(), leg.getReceiverCode()));
            agg.amount = agg.amount.add(leg.getAmount());
            agg.legIds.add(leg.getId());
        }
        return out;
    }

    private int cashLegCount(BatchGroup group) {
        if (group == null) {
            return 0;
        }
        int n = 0;
        for (BatchLeg leg : group.getLegs()) {
            if (leg.getAmount().signum() > 0) {
                n++;
            }
        }
        return n;
    }

    private BigDecimal cashTotal(BatchGroup group) {
        BigDecimal total = zero();
        if (group != null) {
            for (BatchLeg leg : group.getLegs()) {
                if (leg.getAmount().signum() > 0) {
                    total = total.add(leg.getAmount());
                }
            }
        }
        return total.setScale(MONEY, RM);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY, RM);
    }

    private static BatchComparison.BatchRef toRef(NettingBatch b) {
        return new BatchComparison.BatchRef(b.getId(), b.getValuationDate(),
                b.getStatus().name(), b.getCreatedAt(), b.getNote());
    }
}
