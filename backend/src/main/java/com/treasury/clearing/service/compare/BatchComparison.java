package com.treasury.clearing.service.compare;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only side-by-side comparison of two saved netting batches.
 * Everything is grouped by (agreement, settlement currency); monetary
 * amounts of different currencies are never added together — the only
 * batch-level totals are payment-leg counts (a count, not an amount).
 */
public record BatchComparison(
        BatchRef left,
        BatchRef right,
        int leftCashLegCount,
        int rightCashLegCount,
        List<GroupComparison> groups) {

    /** Batch header, carried for display context only. */
    public record BatchRef(String id, LocalDate valuationDate, String status,
                           OffsetDateTime createdAt, String note) {
    }

    /** One (agreement, settlement currency) pocket compared across the two batches. */
    public record GroupComparison(
            String agreementCode,
            String agreementName,
            String settlementCurrency,
            /** BOTH, LEFT_ONLY or RIGHT_ONLY — pockets of one batch only are listed, never merged. */
            String presence,
            String leftGroupId,
            String rightGroupId,
            int leftCashLegCount,
            int rightCashLegCount,
            InvoiceDiffs invoices,
            List<PositionDiff> positions,
            List<LegDiff> paymentLegs,
            boolean zeroDiff) {
    }

    public record InvoiceDiffs(
            List<IncludedInvoice> includedLeftOnly,
            List<IncludedInvoice> includedRightOnly,
            int includedBothCount,
            List<ExcludedInvoice> excludedLeftOnly,
            List<ExcludedInvoice> excludedRightOnly,
            int excludedBothCount,
            List<ReasonChange> exclusionReasonChanged) {
    }

    /** An invoice that took part in the netting of one batch but not the other. */
    public record IncludedInvoice(String claimId, String invoiceNo,
                                  String debtorCode, String creditorCode,
                                  BigDecimal originalAmount, String originalCurrency,
                                  BigDecimal bookedAmount) {
    }

    /** An invoice excluded in one batch but not the other (with its reason). */
    public record ExcludedInvoice(String claimId, String invoiceNo,
                                  String reasonCode, String reasonDetail) {
    }

    /** Excluded in both batches, but for different reasons. */
    public record ReasonChange(String claimId, String invoiceNo,
                               String leftReasonCode, String rightReasonCode) {
    }

    /**
     * Net position of one legal entity in the group's settlement currency
     * (positive = net payer). delta = right - left.
     */
    public record PositionDiff(String entityCode,
                               BigDecimal leftPosition,
                               BigDecimal rightPosition,
                               BigDecimal delta) {
    }

    /**
     * Positive-amount payment legs payer→receiver compared inside one currency.
     * Only pairs whose amount changed (or that exist on one side only) appear.
     */
    public record LegDiff(String payerCode, String receiverCode,
                          BigDecimal leftAmount, BigDecimal rightAmount, BigDecimal delta,
                          String leftLegId, String rightLegId) {
    }
}
