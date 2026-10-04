import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Batch, BatchComparison, GroupComparison, IncludedInvoice } from '../models';
import { ClearingApiService } from '../api.service';

/** Where a difference row should take the user inside one of the two batches. */
export interface CompareJump {
  batchId: string;
  groupId: string;
  target:
    | { kind: 'leg'; payer: string; receiver: string }
    | { kind: 'claim'; claimId: string };
}

/**
 * Side-by-side comparison of two saved batches, per (agreement, settlement
 * currency). Strictly read-only: the panel only fetches the comparison — it
 * never confirms, pays, re-simulates or re-estimates FX. Amounts live inside
 * their own currency group and are never added across currencies; the only
 * batch-level total is the payment-leg count.
 */
@Component({
  selector: 'app-compare-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="panel compare" *ngIf="batches.length">
      <h2>批次对照 <span class="muted sub">只读对比两个已保存方案，不改动确认状态、不重新估算汇率</span></h2>
      <div class="controls">
        <label>基准批次（左）
          <select [(ngModel)]="leftId">
            <option value="">—</option>
            <option *ngFor="let b of batches" [value]="b.id">
              {{ b.id }} · {{ b.valuationDate }} · {{ statusText(b.status) }}
            </option>
          </select>
        </label>
        <label>对照批次（右）
          <select [(ngModel)]="rightId">
            <option value="">—</option>
            <option *ngFor="let b of batches" [value]="b.id">
              {{ b.id }} · {{ b.valuationDate }} · {{ statusText(b.status) }}
            </option>
          </select>
        </label>
        <button class="primary" (click)="compare()" [disabled]="!leftId || !rightId || loading">
          {{ loading ? '对照中…' : '对照两个方案' }}
        </button>
      </div>
      <div class="muted hint">
        差异按「协议＋结算币种」分组并列展示；不同币种的金额绝不相加，仅付款笔数跨组合计。
      </div>
      <div class="bad" *ngIf="error">{{ error }}</div>

      <ng-container *ngIf="result as r">
        <div class="sumline">
          <span>基准 <b>{{ r.left.id }}</b>（{{ statusText(r.left.status) }}）
            ↔ 对照 <b>{{ r.right.id }}</b>（{{ statusText(r.right.status) }}）</span>
          <span>正金额付款腿合计 <b>{{ r.leftCashLegCount }}</b> → <b>{{ r.rightCashLegCount }}</b>
            <span class="delta" [class.down]="legDelta() < 0" [class.up]="legDelta() > 0">
              （{{ legDelta() > 0 ? '+' : '' }}{{ legDelta() }} 笔）
            </span>
          </span>
          <span class="ok" *ngIf="allZero">✓ 两个批次内容完全一致（零差异）</span>
        </div>

        <div class="group" *ngFor="let g of r.groups">
          <div class="ghead">
            <b>{{ g.agreementCode }}</b> · {{ g.agreementName }} · 结算币种 <b>{{ g.settlementCurrency }}</b>
            <span class="tag" [class]="g.presence">{{ presenceText(g.presence) }}</span>
            <span class="tag ZERO" *ngIf="g.zeroDiff">零差异</span>
            <span class="muted">付款腿 {{ g.leftCashLegCount }} → {{ g.rightCashLegCount }} 笔</span>
          </div>

          <!-- 纳入发票差异 -->
          <div class="diff-cols"
               *ngIf="g.invoices.includedLeftOnly.length || g.invoices.includedRightOnly.length">
            <div class="col">
              <h4>仅基准批纳入（{{ g.invoices.includedLeftOnly.length }}）</h4>
              <table *ngIf="g.invoices.includedLeftOnly.length">
                <thead>
                <tr><th>发票</th><th>债务→债权</th><th class="num">原币金额</th>
                  <th class="num">入账 {{ g.settlementCurrency }}</th><th></th></tr>
                </thead>
                <tbody>
                <tr *ngFor="let v of g.invoices.includedLeftOnly">
                  <td>{{ v.invoiceNo }}<div class="muted">{{ v.claimId }}</div></td>
                  <td>{{ v.debtorCode }}→{{ v.creditorCode }}</td>
                  <td class="num">{{ money(v.originalAmount) }} {{ v.originalCurrency }}</td>
                  <td class="num">{{ money(v.bookedAmount) }}</td>
                  <td><button (click)="jumpClaim(g, 'left', v)">追溯</button></td>
                </tr>
                </tbody>
              </table>
            </div>
            <div class="col">
              <h4>仅对照批纳入（{{ g.invoices.includedRightOnly.length }}）</h4>
              <table *ngIf="g.invoices.includedRightOnly.length">
                <thead>
                <tr><th>发票</th><th>债务→债权</th><th class="num">原币金额</th>
                  <th class="num">入账 {{ g.settlementCurrency }}</th><th></th></tr>
                </thead>
                <tbody>
                <tr *ngFor="let v of g.invoices.includedRightOnly">
                  <td>{{ v.invoiceNo }}<div class="muted">{{ v.claimId }}</div></td>
                  <td>{{ v.debtorCode }}→{{ v.creditorCode }}</td>
                  <td class="num">{{ money(v.originalAmount) }} {{ v.originalCurrency }}</td>
                  <td class="num">{{ money(v.bookedAmount) }}</td>
                  <td><button (click)="jumpClaim(g, 'right', v)">追溯</button></td>
                </tr>
                </tbody>
              </table>
            </div>
          </div>
          <div class="muted line" *ngIf="!g.invoices.includedLeftOnly.length && !g.invoices.includedRightOnly.length">
            纳入发票一致（两批共同 {{ g.invoices.includedBothCount }} 张）。
          </div>

          <!-- 排除发票差异 -->
          <div class="diff-cols"
               *ngIf="g.invoices.excludedLeftOnly.length || g.invoices.excludedRightOnly.length">
            <div class="col">
              <h4>仅基准批排除（{{ g.invoices.excludedLeftOnly.length }}）</h4>
              <table *ngIf="g.invoices.excludedLeftOnly.length">
                <thead><tr><th>发票</th><th>原因</th><th></th></tr></thead>
                <tbody>
                <tr *ngFor="let v of g.invoices.excludedLeftOnly">
                  <td>{{ v.invoiceNo }}<div class="muted">{{ v.claimId }}</div></td>
                  <td><span class="tag DISPUTED">{{ v.reasonCode }}</span>
                    <div class="muted">{{ v.reasonDetail }}</div></td>
                  <td><button (click)="jumpClaim(g, 'left', v)">追溯</button></td>
                </tr>
                </tbody>
              </table>
            </div>
            <div class="col">
              <h4>仅对照批排除（{{ g.invoices.excludedRightOnly.length }}）</h4>
              <table *ngIf="g.invoices.excludedRightOnly.length">
                <thead><tr><th>发票</th><th>原因</th><th></th></tr></thead>
                <tbody>
                <tr *ngFor="let v of g.invoices.excludedRightOnly">
                  <td>{{ v.invoiceNo }}<div class="muted">{{ v.claimId }}</div></td>
                  <td><span class="tag DISPUTED">{{ v.reasonCode }}</span>
                    <div class="muted">{{ v.reasonDetail }}</div></td>
                  <td><button (click)="jumpClaim(g, 'right', v)">追溯</button></td>
                </tr>
                </tbody>
              </table>
            </div>
          </div>
          <div class="line" *ngIf="g.invoices.exclusionReasonChanged.length">
            <div *ngFor="let c of g.invoices.exclusionReasonChanged" class="muted">
              排除原因变化：{{ c.invoiceNo }}（{{ c.claimId }}）
              {{ c.leftReasonCode }} → {{ c.rightReasonCode }}
            </div>
          </div>

          <!-- 各法人净头寸 -->
          <h4>各法人净头寸（{{ g.settlementCurrency }}，正 = 净应付）</h4>
          <table>
            <thead>
            <tr><th>法人</th><th class="num">基准批</th><th class="num">对照批</th><th class="num">Δ</th></tr>
            </thead>
            <tbody>
            <tr *ngFor="let p of g.positions" [class.changed]="p.delta !== 0">
              <td>{{ p.entityCode }}</td>
              <td class="num">{{ money(p.leftPosition) }}</td>
              <td class="num">{{ money(p.rightPosition) }}</td>
              <td class="num delta" [class.up]="p.delta > 0" [class.down]="p.delta < 0">
                {{ signed(p.delta) }}
              </td>
            </tr>
            </tbody>
          </table>

          <!-- 正金额付款腿差异 -->
          <h4>正金额付款腿差异（{{ g.settlementCurrency }}）</h4>
          <div class="muted line" *ngIf="!g.paymentLegs.length">付款腿一致，无差异。</div>
          <table *ngIf="g.paymentLegs.length">
            <thead>
            <tr><th>付款方</th><th>收款方</th><th class="num">基准批</th>
              <th class="num">对照批</th><th class="num">Δ</th><th>跳转到图边</th></tr>
            </thead>
            <tbody>
            <tr *ngFor="let l of g.paymentLegs">
              <td>{{ l.payerCode }}</td>
              <td>{{ l.receiverCode }}</td>
              <td class="num">{{ money(l.leftAmount) }}</td>
              <td class="num">{{ money(l.rightAmount) }}</td>
              <td class="num delta" [class.up]="l.delta > 0" [class.down]="l.delta < 0">
                {{ signed(l.delta) }}
              </td>
              <td class="jumps">
                <button *ngIf="l.leftLegId" (click)="jumpLeg(g, 'left', l)">基准批</button>
                <button *ngIf="l.rightLegId" (click)="jumpLeg(g, 'right', l)">对照批</button>
              </td>
            </tr>
            </tbody>
          </table>
        </div>
      </ng-container>
    </section>
  `,
  styles: [`
    .compare h2 .sub { font-size: 12px; font-weight: 400; margin-left: 8px; }
    .controls { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
    .controls label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--muted); }
    .controls select { min-width: 300px; }
    .hint { margin-top: 6px; font-size: 12px; }
    .sumline { display: flex; gap: 22px; align-items: center; flex-wrap: wrap;
      margin: 12px 0 4px; padding: 8px 10px; background: var(--panel-2); border-radius: 6px; }
    .group { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; margin-top: 12px; }
    .ghead { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
    h4 { font-size: 12.5px; margin: 12px 0 6px; color: var(--muted); font-weight: 600; }
    .diff-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
    .col h4 { margin-top: 4px; }
    .line { margin: 4px 0; font-size: 12.5px; }
    .delta.up { color: var(--bad); }
    .delta.down { color: var(--good); }
    tr.changed td { background: rgba(251, 191, 36, .06); }
    .tag.BOTH { color: var(--accent); border-color: var(--accent); }
    .tag.LEFT_ONLY, .tag.RIGHT_ONLY { color: var(--warn); border-color: var(--warn); }
    .tag.ZERO { color: var(--good); border-color: var(--good); }
    .tag.DISPUTED { color: var(--bad); border-color: var(--bad); }
    .ok { color: var(--good); }
    .bad { color: var(--bad); margin-top: 6px; }
    .jumps button { padding: 2px 10px; margin-right: 6px; font-size: 12px; }
    td .muted { font-size: 11px; }
  `],
})
export class ComparePanelComponent implements OnChanges {
  @Input() batches: Batch[] = [];
  @Output() jump = new EventEmitter<CompareJump>();

  leftId = '';
  rightId = '';
  loading = false;
  error = '';
  result: BatchComparison | null = null;

  constructor(private api: ClearingApiService) {}

  ngOnChanges(): void {
    // Newest batch on the right, the previous one on the left — the usual
    // "what changed since the last trial" question.
    if (!this.leftId && !this.rightId && this.batches.length >= 2) {
      this.rightId = this.batches[0].id;
      this.leftId = this.batches[1].id;
    }
  }

  compare(): void {
    if (!this.leftId || !this.rightId) {
      return;
    }
    this.loading = true;
    this.error = '';
    this.api.compareBatches(this.leftId, this.rightId).subscribe({
      next: (r) => {
        this.result = r;
        this.loading = false;
      },
      error: (e) => {
        this.loading = false;
        this.error = e?.error?.error ?? '对照失败';
      },
    });
  }

  get allZero(): boolean {
    return !!this.result && this.result.groups.every((g) => g.zeroDiff);
  }

  legDelta(): number {
    return this.result ? this.result.rightCashLegCount - this.result.leftCashLegCount : 0;
  }

  jumpLeg(g: GroupComparison, side: 'left' | 'right',
          l: { payerCode: string; receiverCode: string }): void {
    const batchId = side === 'left' ? this.result!.left.id : this.result!.right.id;
    const groupId = side === 'left' ? g.leftGroupId : g.rightGroupId;
    if (groupId) {
      this.jump.emit({ batchId, groupId,
        target: { kind: 'leg', payer: l.payerCode, receiver: l.receiverCode } });
    }
  }

  jumpClaim(g: GroupComparison, side: 'left' | 'right', v: IncludedInvoice | { claimId: string }): void {
    const batchId = side === 'left' ? this.result!.left.id : this.result!.right.id;
    const groupId = side === 'left' ? g.leftGroupId : g.rightGroupId;
    if (groupId) {
      this.jump.emit({ batchId, groupId, target: { kind: 'claim', claimId: v.claimId } });
    }
  }

  statusText(s: string): string {
    return { SIMULATED: '试算', CONFIRMED: '已确认', PAID_SIMULATED: '模拟已付' }[s] ?? s;
  }

  presenceText(p: string): string {
    return { BOTH: '两批均有', LEFT_ONLY: '仅基准批', RIGHT_ONLY: '仅对照批' }[p] ?? p;
  }

  money(v: number): string {
    return (v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  signed(v: number): string {
    return (v > 0 ? '+' : '') + this.money(v);
  }
}
