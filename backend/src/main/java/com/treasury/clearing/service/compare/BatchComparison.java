package com.treasury.clearing.service.compare;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only side-by-side comparison of two already-saved batches.
 *
 * <p>Groups are matched strictly by (agreement, settlement currency); every
 * amount inside a {@link GroupComparison} is denominated in that group's
 * settlement currency. Differences are never summed across groups, so two
 * different currencies can never collapse into a single figure.
 */
public record BatchComparison(BatchRef left,
                              BatchRef right,
                              List<GroupComparison> groups,
                              boolean zeroDifference) {

    /** Which side of the comparison a matched row exists on. */
    public enum Presence { BOTH, LEFT_ONLY, RIGHT_ONLY }

    /** How one claim participated in one batch's group. */
    public enum InvoiceState { INCLUDED, EXCLUDED, ABSENT }

    /** Header facts of one compared batch. */
    public record BatchRef(String id,
                           LocalDate valuationDate,
                           String status,
                           OffsetDateTime createdAt,
                           String note) {
    }

    /**
     * Comparison of the two groups sharing one (agreement, settlement currency)
     * key. A group existing on only one side is reported whole on that side;
     * groups of different agreements or currencies are always reported apart.
     */
    public record GroupComparison(String key,
                                  String agreementCode,
                                  String agreementName,
                                  String settlementCurrency,
                                  Presence presence,
                                  String leftGroupId,
                                  String rightGroupId,
                                  List<InvoiceDiff> invoices,
                                  List<PositionDiff> positions,
                                  List<LegDiff> legs,
                                  int invoiceChangeCount,
                                  int legChangeCount,
                                  int leftCashLegCount,
                                  int rightCashLegCount,
                                  BigDecimal leftNetTotal,
                                  BigDecimal rightNetTotal,
                                  BigDecimal netTotalDiff,
                                  boolean zeroDifference) {
    }

    /** One claim's inclusion/exclusion state on both sides of one group. */
    public record InvoiceDiff(String claimId,
                              String invoiceNo,
                              String debtorCode,
                              String creditorCode,
                              BigDecimal amount,
                              String currency,
                              InvoiceState leftState,
                              InvoiceState rightState,
                              String leftReasonCode,
                              String rightReasonCode,
                              boolean changed) {
    }

    /** Net position of one legal entity (positive = net payer), in settlement currency. */
    public record PositionDiff(String entityCode,
                               BigDecimal leftNet,
                               BigDecimal rightNet,
                               BigDecimal diff) {
    }

    /**
     * Positive-amount payment legs between one payer/receiver pair. Zero-amount
     * set-off memos are trace records, not payments, and never appear here.
     * The leg ids let the UI jump straight back to the edge in the source batch.
     */
    public record LegDiff(String payerCode,
                          String receiverCode,
                          Presence presence,
                          BigDecimal leftAmount,
                          BigDecimal rightAmount,
                          BigDecimal diff,
                          List<String> leftLegIds,
                          List<String> rightLegIds,
                          String leftGroupId,
                          String rightGroupId) {
    }
}
