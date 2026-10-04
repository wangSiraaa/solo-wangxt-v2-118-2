package com.treasury.clearing.service.compare;

import com.treasury.clearing.domain.BatchExclusion;
import com.treasury.clearing.domain.BatchGroup;
import com.treasury.clearing.domain.BatchLeg;
import com.treasury.clearing.domain.LedgerSide;
import com.treasury.clearing.domain.LegItem;
import com.treasury.clearing.domain.NettingBatch;
import com.treasury.clearing.service.BatchService;
import com.treasury.clearing.service.compare.BatchComparison.BatchRef;
import com.treasury.clearing.service.compare.BatchComparison.ExcludedInvoice;
import com.treasury.clearing.service.compare.BatchComparison.GroupComparison;
import com.treasury.clearing.service.compare.BatchComparison.IncludedInvoice;
import com.treasury.clearing.service.compare.BatchComparison.InvoiceDiffs;
import com.treasury.clearing.service.compare.BatchComparison.LegDiff;
import com.treasury.clearing.service.compare.BatchComparison.PositionDiff;
import com.treasury.clearing.service.compare.BatchComparison.ReasonChange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only comparison of two saved batches. It never mutates batch or claim
 * state and never re-estimates FX: every figure is derived from the persisted
 * groups, legs, items and exclusions of the two stored plans. Amounts only
 * ever meet inside one (agreement, settlement currency) pocket, so different
 * currencies can never be summed into a single difference.
 */
@Service
public class BatchCompareService {

    private static final int MONEY_SCALE = 2;

    private final BatchService batchService;

    public BatchCompareService(BatchService batchService) {
        this.batchService = batchService;
    }

    @Transactional(readOnly = true)
    public BatchComparison compare(String leftBatchId, String rightBatchId) {
        NettingBatch left = batchService.getBatch(leftBatchId);
        NettingBatch right = batchService.getBatch(rightBatchId);

        Map<GroupKey, GroupFacts> leftFacts = factsOf(left);
        Map<GroupKey, GroupFacts> rightFacts = factsOf(right);

        Set<GroupKey> keys = new TreeSet<>(leftFacts.keySet());
        keys.addAll(rightFacts.keySet());

        List<GroupComparison> groups = new ArrayList<>();
        for (GroupKey key : keys) {
            groups.add(compareGroup(key, leftFacts.get(key), rightFacts.get(key)));
        }
        return new BatchComparison(refOf(left), refOf(right),
                cashLegCount(leftFacts), cashLegCount(rightFacts), groups);
    }

    private static BatchRef refOf(NettingBatch b) {
        return new BatchRef(b.getId(), b.getValuationDate(), b.getStatus().name(),
                b.getCreatedAt(), b.getNote() == null ? "" : b.getNote());
    }

    private static int cashLegCount(Map<GroupKey, GroupFacts> facts) {
        return facts.values().stream().mapToInt(f -> f.cashLegCount).sum();
    }

    // ---------------- per-group extraction ----------------

    private record GroupKey(String agreementCode, String settlementCurrency)
            implements Comparable<GroupKey> {
        @Override
        public int compareTo(GroupKey o) {
            int c = agreementCode.compareTo(o.agreementCode);
            return c != 0 ? c : settlementCurrency.compareTo(o.settlementCurrency);
        }
    }

    private record LegKey(String payer, String receiver) implements Comparable<LegKey> {
        @Override
        public int compareTo(LegKey o) {
            int c = payer.compareTo(o.payer);
            return c != 0 ? c : receiver.compareTo(o.receiver);
        }
    }

    /** Everything the comparison needs from one persisted group. */
    private static final class GroupFacts {
        final String groupId;
        final String agreementName;
        int cashLegCount;
        final Map<String, ClaimAcc> included = new TreeMap<>();
        final Map<String, BatchExclusion> excluded = new TreeMap<>();
        final Map<String, BigDecimal> positions = new TreeMap<>();
        final Map<LegKey, LegAcc> legs = new TreeMap<>();

        GroupFacts(String groupId, String agreementName) {
            this.groupId = groupId;
            this.agreementName = agreementName;
        }
    }

    private static final class LegAcc {
        BigDecimal amount = zero();
        String firstLegId;

        void add(BigDecimal a, String legId) {
            amount = amount.add(a);
            if (firstLegId == null) {
                firstLegId = legId;
            }
        }
    }

    private static final class ClaimAcc {
        final String invoiceNo;
        final String debtorCode;
        final String creditorCode;
        final String originalCurrency;
        BigDecimal originalAmount = zero();
        BigDecimal bookedAmount = zero();

        ClaimAcc(LegItem item) {
            this.invoiceNo = item.getInvoiceNo();
            this.debtorCode = item.getDebtorCode();
            this.creditorCode = item.getCreditorCode();
            this.originalCurrency = item.getOriginalCurrency();
        }
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private Map<GroupKey, GroupFacts> factsOf(NettingBatch batch) {
        Map<GroupKey, GroupFacts> out = new TreeMap<>();
        for (BatchGroup g : batch.getGroups()) {
            GroupKey key = new GroupKey(g.getAgreementCode(), g.getSettlementCurrency());
            GroupFacts f = new GroupFacts(g.getId(), g.getAgreementName());
            for (BatchLeg leg : g.getLegs()) {
                if (leg.getAmount().signum() > 0) {
                    // Positive-amount payment legs drive both the leg diff and
                    // the per-entity net positions (zero-amount memos move nothing).
                    f.cashLegCount++;
                    f.positions.merge(leg.getPayerCode(), leg.getAmount(), BigDecimal::add);
                    f.positions.merge(leg.getReceiverCode(), leg.getAmount().negate(), BigDecimal::add);
                    f.legs.computeIfAbsent(new LegKey(leg.getPayerCode(), leg.getReceiverCode()),
                            k -> new LegAcc()).add(leg.getAmount(), leg.getId());
                }
                for (LegItem it : leg.getItems()) {
                    ClaimAcc acc = f.included.computeIfAbsent(it.getClaimId(), id -> new ClaimAcc(it));
                    if (it.getSide() == LedgerSide.PAYER) {
                        // Each claim's payer side is partitioned exactly once across
                        // the group's legs, so summing it rebuilds the claim totals.
                        acc.originalAmount = acc.originalAmount.add(it.getOriginalAmount());
                        acc.bookedAmount = acc.bookedAmount.add(it.getConvertedBooked());
                    }
                }
            }
            for (BatchExclusion ex : g.getExclusions()) {
                f.excluded.put(ex.getClaimId(), ex);
            }
            if (out.put(key, f) != null) {
                throw new IllegalStateException("duplicate group pocket " + key
                        + " in batch " + batch.getId());
            }
        }
        return out;
    }

    // ---------------- per-group comparison ----------------

    private GroupComparison compareGroup(GroupKey key, GroupFacts l, GroupFacts r) {
        String presence = l != null ? (r != null ? "BOTH" : "LEFT_ONLY") : "RIGHT_ONLY";

        List<IncludedInvoice> includedLeftOnly = new ArrayList<>();
        List<IncludedInvoice> includedRightOnly = new ArrayList<>();
        int includedBoth = 0;
        Set<String> claimIds = new TreeSet<>();
        if (l != null) {
            claimIds.addAll(l.included.keySet());
        }
        if (r != null) {
            claimIds.addAll(r.included.keySet());
        }
        for (String id : claimIds) {
            ClaimAcc a = l != null ? l.included.get(id) : null;
            ClaimAcc b = r != null ? r.included.get(id) : null;
            if (a != null && b != null) {
                includedBoth++;
            } else if (a != null) {
                includedLeftOnly.add(toInvoice(id, a));
            } else {
                includedRightOnly.add(toInvoice(id, b));
            }
        }

        List<ExcludedInvoice> excludedLeftOnly = new ArrayList<>();
        List<ExcludedInvoice> excludedRightOnly = new ArrayList<>();
        List<ReasonChange> reasonChanged = new ArrayList<>();
        int excludedBoth = 0;
        Set<String> excludedIds = new TreeSet<>();
        if (l != null) {
            excludedIds.addAll(l.excluded.keySet());
        }
        if (r != null) {
            excludedIds.addAll(r.excluded.keySet());
        }
        for (String id : excludedIds) {
            BatchExclusion a = l != null ? l.excluded.get(id) : null;
            BatchExclusion b = r != null ? r.excluded.get(id) : null;
            if (a != null && b != null) {
                excludedBoth++;
                if (!a.getReasonCode().equals(b.getReasonCode())) {
                    reasonChanged.add(new ReasonChange(id, nv(a.getInvoiceNo()),
                            a.getReasonCode(), b.getReasonCode()));
                }
            } else if (a != null) {
                excludedLeftOnly.add(toExcluded(a));
            } else {
                excludedRightOnly.add(toExcluded(b));
            }
        }

        Set<String> entities = new TreeSet<>();
        if (l != null) {
            entities.addAll(l.positions.keySet());
        }
        if (r != null) {
            entities.addAll(r.positions.keySet());
        }
        List<PositionDiff> positions = new ArrayList<>();
        for (String e : entities) {
            BigDecimal lp = positionOf(l, e);
            BigDecimal rp = positionOf(r, e);
            positions.add(new PositionDiff(e, lp, rp,
                    rp.subtract(lp).setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
        }

        Set<LegKey> legKeys = new TreeSet<>();
        if (l != null) {
            legKeys.addAll(l.legs.keySet());
        }
        if (r != null) {
            legKeys.addAll(r.legs.keySet());
        }
        List<LegDiff> legDiffs = new ArrayList<>();
        for (LegKey k : legKeys) {
            LegAcc a = l != null ? l.legs.get(k) : null;
            LegAcc b = r != null ? r.legs.get(k) : null;
            BigDecimal la = a != null ? a.amount : zero();
            BigDecimal ra = b != null ? b.amount : zero();
            if (la.compareTo(ra) == 0) {
                continue; // unchanged payment pair is not a difference
            }
            legDiffs.add(new LegDiff(k.payer(), k.receiver(),
                    la.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    ra.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    ra.subtract(la).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    a != null ? a.firstLegId : "", b != null ? b.firstLegId : ""));
        }

        boolean zeroDiff = includedLeftOnly.isEmpty() && includedRightOnly.isEmpty()
                && excludedLeftOnly.isEmpty() && excludedRightOnly.isEmpty()
                && reasonChanged.isEmpty() && legDiffs.isEmpty()
                && positions.stream().allMatch(p -> p.delta().signum() == 0);

        String name = l != null ? l.agreementName : r.agreementName;
        return new GroupComparison(key.agreementCode(), name == null ? "" : name,
                key.settlementCurrency(), presence,
                l != null ? l.groupId : "", r != null ? r.groupId : "",
                l != null ? l.cashLegCount : 0, r != null ? r.cashLegCount : 0,
                new InvoiceDiffs(includedLeftOnly, includedRightOnly, includedBoth,
                        excludedLeftOnly, excludedRightOnly, excludedBoth, reasonChanged),
                positions, legDiffs, zeroDiff);
    }

    private static BigDecimal positionOf(GroupFacts f, String entity) {
        if (f == null) {
            return zero();
        }
        BigDecimal p = f.positions.get(entity);
        return p == null ? zero() : p.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static IncludedInvoice toInvoice(String claimId, ClaimAcc a) {
        return new IncludedInvoice(claimId, nv(a.invoiceNo), a.debtorCode, a.creditorCode,
                a.originalAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP), a.originalCurrency,
                a.bookedAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private static ExcludedInvoice toExcluded(BatchExclusion ex) {
        return new ExcludedInvoice(ex.getClaimId(), nv(ex.getInvoiceNo()),
                ex.getReasonCode(), nv(ex.getReasonDetail()));
    }

    private static String nv(String s) {
        return s == null ? "" : s;
    }
}
