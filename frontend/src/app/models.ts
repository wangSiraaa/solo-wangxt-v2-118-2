export interface LegalEntity {
  code: string;
  name: string;
  functionalCurrency: string;
  active: boolean;
}

export interface Agreement {
  code: string;
  name: string;
  allowsNetting: boolean;
  crossCurrency: boolean;
  settlementCurrency: string;
  roundingBearerCode: string;
  members: string[];
  currencies: string[];
  effectiveFrom: string;
  effectiveTo?: string;
  fxAsOf?: string;
}

export interface Claim {
  id: string;
  invoiceNo: string;
  agreementCode: string;
  debtorCode: string;
  creditorCode: string;
  amount: number;
  currency: string;
  status: 'OPEN' | 'PLEDGED' | 'DISPUTED' | 'SETTLED';
  invoiceDate: string;
  dueDate?: string;
  description: string;
  offsetBatchId?: string;
}

export interface FxRate {
  baseCurrency: string;
  quoteCurrency: string;
  asOf: string;
  rate: number;
  source: string;
}

export interface LegItem {
  id: string;
  claimId: string;
  invoiceNo: string;
  debtorCode: string;
  creditorCode: string;
  side: 'PAYER' | 'RECEIVER';
  originalAmount: number;
  originalCurrency: string;
  fxRate: number;
  fxAsOf?: string;
  fxSource?: string;
  fxInverted: boolean;
  convertedExact: number;
  convertedBooked: number;
  roundingDiff: number;
  roundingBearerCode: string;
}

export interface Exclusion {
  id: string;
  claimId: string;
  invoiceNo: string;
  reasonCode: string;
  reasonDetail: string;
}

export interface BatchLeg {
  id: string;
  payerCode: string;
  receiverCode: string;
  amount: number;
  settlementCurrency: string;
  isOriginal: boolean;
  isMemo: boolean;
  amountExact?: number;
  residualAdjustment: number;
  seqNo: number;
  paidSimulatedAt?: string;
  items: LegItem[];
}

export interface BatchGroup {
  id: string;
  agreementCode: string;
  agreementName: string;
  settlementCurrency: string;
  crossCurrency: boolean;
  passThrough: boolean;
  originalLegCount: number;
  nettedLegCount: number;
  grossAmount: number;
  netAmount: number;
  roundingDiffTotal: number;
  roundingResidual: number;
  roundingBearerCode: string;
  legs: BatchLeg[];
  exclusions: Exclusion[];
}

export interface Batch {
  id: string;
  valuationDate: string;
  status: 'SIMULATED' | 'CONFIRMED' | 'PAID_SIMULATED';
  incomingClaimCount: number;
  includedClaimCount: number;
  excludedClaimCount: number;
  originalLegCount: number;
  nettedLegCount: number;
  grossAmount: number;
  netAmount: number;
  createdAt: string;
  confirmedAt?: string;
  paidSimulatedAt?: string;
  note?: string;
  groups: BatchGroup[];
}

// ---------------- batch comparison (read-only) ----------------

export type ComparePresence = 'BOTH' | 'LEFT_ONLY' | 'RIGHT_ONLY';
export type InvoiceState = 'INCLUDED' | 'EXCLUDED' | 'ABSENT';

export interface BatchRef {
  id: string;
  valuationDate: string;
  status: string;
  createdAt: string;
  note?: string;
}

export interface InvoiceDiff {
  claimId: string;
  invoiceNo: string;
  debtorCode: string;
  creditorCode: string;
  amount: number;
  currency: string;
  leftState: InvoiceState;
  rightState: InvoiceState;
  leftReasonCode?: string;
  rightReasonCode?: string;
  changed: boolean;
}

export interface PositionDiff {
  entityCode: string;
  leftNet: number;
  rightNet: number;
  diff: number;
}

export interface LegDiff {
  payerCode: string;
  receiverCode: string;
  presence: ComparePresence;
  leftAmount?: number;
  rightAmount?: number;
  diff: number;
  leftLegIds: string[];
  rightLegIds: string[];
  leftGroupId?: string;
  rightGroupId?: string;
}

export interface GroupComparison {
  key: string;
  agreementCode: string;
  agreementName: string;
  settlementCurrency: string;
  presence: ComparePresence;
  leftGroupId?: string;
  rightGroupId?: string;
  invoices: InvoiceDiff[];
  positions: PositionDiff[];
  legs: LegDiff[];
  invoiceChangeCount: number;
  legChangeCount: number;
  leftCashLegCount: number;
  rightCashLegCount: number;
  leftNetTotal: number;
  rightNetTotal: number;
  netTotalDiff: number;
  zeroDifference: boolean;
}

export interface BatchComparison {
  left: BatchRef;
  right: BatchRef;
  groups: GroupComparison[];
  zeroDifference: boolean;
}

/** Navigation request from a comparison row back into one batch's graph/trace. */
export interface CompareJump {
  batchId: string;
  groupId: string;
  legId?: string;
  claimId?: string;
}
