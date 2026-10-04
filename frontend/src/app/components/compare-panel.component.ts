import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  BatchComparison, CompareJump, ComparePresence,
  GroupComparison, InvoiceDiff, InvoiceState, LegDiff,
} from '../models';

/**
 * Read-only side-by-side comparison of two saved batches.
 * Every group (agreement × settlement currency) is shown in its own section;
 * amounts of different currencies are never merged into one figure.
 * Rows link back to the source batch's graph edge / invoice trace.
 */
@Component({
  selector: 'app-compare-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="cmp" *ngIf="comparison as cmp">
      <div class="head">
        <h2>批次对照 <span class="muted">（只读：不改动确认状态，不重新估算汇率）</span></h2>
        <button (click)="closed.emit()">×</button>
      </div>

      <div class="refs">
        <div class="ref">
          <span class="side">基准 · 左</span>
          <b>{{ cmp.left.id }}</b>
          <span class="muted">{{ cmp.left.valuationDate }} · {{ statusText(cmp.left.status) }}</span>
        </div>
        <div class="ref">
          <span class="side">对照 · 右</span>
          <b>{{ cmp.right.id }}</b>
          <span class="muted">{{ cmp.right.valuationDate }} · {{ statusText(cmp.right.status) }}</span>
        </div>
      </div>

      <div class="zero" *ngIf="cmp.zeroDifference">
        ✓ 两批次结果完全一致：发票纳入/排除、各法人净头寸与正金额付款腿均为零差异。
      </div>
      <div class="note-line" *ngIf="!cmp.zeroDifference">
        按“协议＋结算币种”分组对照；不同协议或币种的分组各自独立展示，不合并求差。
      </div>

      <section class="grp" *ngFor="let g of cmp.groups">
        <div class="grp-head">
          <h3>{{ g.agreementCode }} · {{ g.settlementCurrency }}</h3>
          <span class="tag" [class]="g.presence">{{ presenceText(g.presence) }}</span>
          <span class="ok" *ngIf="g.zeroDifference">零差异</span>
          <span class="bad" *ngIf="!g.zeroDifference">
            发票差异 {{ g.invoiceChangeCount }} · 付款腿差异 {{ g.legChangeCount }}
          </span>
        </div>
        <div class="muted grp-meta">
          {{ g.agreementName }} · 现金付款腿 {{ g.leftCashLegCount }} → {{ g.rightCashLegCount }} 笔 ·
          净应付 {{ money(g.leftNetTotal) }} → {{ money(g.rightNetTotal) }}
          （Δ {{ signed(g.netTotalDiff) }}）{{ g.settlementCurrency }}
        </div>

        <!-- 正金额付款腿差异 -->
        <h4>正金额付款腿（{{ g.settlementCurrency }}）</h4>
        <table>
          <thead>
          <tr>
            <th>付款方 → 收款方</th>
            <th class="num">左批次金额</th><th class="num">右批次金额</th>
            <th class="num">差异(右−左)</th><th>状态</th><th>跳转到图边</th>
          </tr>
          </thead>
          <tbody>
          <tr *ngFor="let l of g.legs" [class.changed]="legChanged(l)">
            <td>{{ l.payerCode }} → {{ l.receiverCode }}</td>
            <td class="num">{{ l.leftAmount != null ? money(l.leftAmount) : '—' }}</td>
            <td class="num">{{ l.rightAmount != null ? money(l.rightAmount) : '—' }}</td>
            <td class="num" [class.bad]="l.diff !== 0">{{ signed(l.diff) }}</td>
            <td><span class="tag" [class]="l.presence">{{ legStateText(l) }}</span></td>
            <td class="jumps">
              <button *ngIf="l.leftLegIds.length" (click)="jumpLeg(cmp, g, l, 'left')">左批次</button>
              <button *ngIf="l.rightLegIds.length" (click)="jumpLeg(cmp, g, l, 'right')">右批次</button>
            </td>
          </tr>
          <tr *ngIf="!g.legs.length">
            <td colspan="6" class="muted">两侧均无正金额付款腿（全部抵销或无纳入债权）。</td>
          </tr>
          </tbody>
        </table>

        <!-- 各法人净头寸 -->
        <h4>各法人净头寸（{{ g.settlementCurrency }}，正 = 净付款）</h4>
        <table>
          <thead>
          <tr>
            <th>法人</th><th class="num">左批次净头寸</th>
            <th class="num">右批次净头寸</th><th class="num">差异(右−左)</th>
          </tr>
          </thead>
          <tbody>
          <tr *ngFor="let p of g.positions" [class.changed]="p.diff !== 0">
            <td>{{ p.entityCode }}</td>
            <td class="num">{{ signed(p.leftNet) }}</td>
            <td class="num">{{ signed(p.rightNet) }}</td>
            <td class="num" [class.bad]="p.diff !== 0">{{ signed(p.diff) }}</td>
          </tr>
          <tr *ngIf="!g.positions.length">
            <td colspan="4" class="muted">两侧均无现金头寸。</td>
          </tr>
          </tbody>
        </table>

        <!-- 发票纳入/排除差异 -->
        <h4>
          发票纳入 / 排除
          <label class="toggle">
            <input type="checkbox" [(ngModel)]="onlyChanged"> 仅看差异（{{ g.invoiceChangeCount }}）
          </label>
        </h4>
        <table>
          <thead>
          <tr>
            <th>发票</th><th>方向</th><th class="num">金额</th>
            <th>左批次</th><th>右批次</th><th>跳转到发票追溯</th>
          </tr>
          </thead>
          <tbody>
          <tr *ngFor="let inv of visibleInvoices(g)" [class.changed]="inv.changed">
            <td>
              <div>{{ inv.invoiceNo || '—' }}</div>
              <div class="muted">{{ inv.claimId }}</div>
            </td>
            <td>{{ inv.debtorCode }} → {{ inv.creditorCode }}</td>
            <td class="num">{{ money(inv.amount) }} {{ inv.currency }}</td>
            <td>
              <span class="tag" [class]="inv.leftState">{{ stateText(inv.leftState) }}</span>
              <span class="muted" *ngIf="inv.leftReasonCode"> {{ inv.leftReasonCode }}</span>
            </td>
            <td>
              <span class="tag" [class]="inv.rightState">{{ stateText(inv.rightState) }}</span>
              <span class="muted" *ngIf="inv.rightReasonCode"> {{ inv.rightReasonCode }}</span>
            </td>
            <td class="jumps">
              <button *ngIf="inv.leftState !== 'ABSENT' && g.leftGroupId"
                      (click)="jumpInvoice(cmp, g, inv, 'left')">左批次</button>
              <button *ngIf="inv.rightState !== 'ABSENT' && g.rightGroupId"
                      (click)="jumpInvoice(cmp, g, inv, 'right')">右批次</button>
            </td>
          </tr>
          <tr *ngIf="!visibleInvoices(g).length">
            <td colspan="6" class="muted">无差异发票；取消“仅看差异”可查看全部。</td>
          </tr>
          </tbody>
        </table>
      </section>
    </div>
  `,
  styles: [`
    .cmp { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 14px 16px; margin: 14px 0; }
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
    .head h2 { font-size: 16px; }
    .head button { padding: 2px 9px; }
    .refs { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
    .ref { background: var(--panel-2); border: 1px solid var(--line); border-radius: 6px; padding: 8px 10px; display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
    .side { font-size: 11px; color: var(--accent); border: 1px solid var(--accent); border-radius: 999px; padding: 1px 8px; }
    .zero { background: #052e1b; border: 1px solid var(--good); color: #a7f3d0; border-radius: 6px; padding: 8px 12px; margin-bottom: 10px; }
    .note-line { background: #0b2447; border: 1px solid #1d4ed8; color: #bfdbfe; border-radius: 6px; padding: 8px 12px; margin-bottom: 10px; font-size: 12.5px; }
    .grp { border-top: 1px solid var(--line); padding-top: 10px; margin-top: 10px; }
    .grp-head { display: flex; gap: 10px; align-items: center; margin-bottom: 4px; }
    .grp-head h3 { font-size: 14.5px; }
    .grp-meta { font-size: 12.5px; margin-bottom: 8px; }
    h4 { font-size: 13px; margin: 12px 0 6px; font-weight: 600; }
    .toggle { font-size: 12px; color: var(--muted); font-weight: 400; margin-left: 10px; }
    tr.changed { background: rgba(251,191,36,.06); }
    .ok { color: var(--good); font-size: 12px; }
    .bad { color: var(--bad); }
    .jumps { white-space: nowrap; }
    .jumps button { padding: 2px 8px; font-size: 11.5px; margin-right: 4px; }
    .tag.BOTH { color: var(--muted); }
    .tag.LEFT_ONLY, .tag.RIGHT_ONLY { color: var(--warn); border-color: var(--warn); }
    .tag.INCLUDED { color: #7dd3fc; border-color: #38bdf8; }
    .tag.EXCLUDED { color: #fca5a5; border-color: #f87171; }
    .tag.ABSENT { color: var(--muted); border-style: dashed; }
  `],
})
export class ComparePanelComponent {
  @Input() comparison: BatchComparison | null = null;
  @Output() jumpTo = new EventEmitter<CompareJump>();
  @Output() closed = new EventEmitter<void>();

  onlyChanged = true;

  visibleInvoices(g: GroupComparison): InvoiceDiff[] {
    return this.onlyChanged ? g.invoices.filter((i) => i.changed) : g.invoices;
  }

  legChanged(l: LegDiff): boolean {
    return l.presence !== 'BOTH' || l.diff !== 0;
  }

  jumpLeg(cmp: BatchComparison, g: GroupComparison, l: LegDiff, side: 'left' | 'right'): void {
    const groupId = side === 'left' ? l.leftGroupId : l.rightGroupId;
    const legId = side === 'left' ? l.leftLegIds[0] : l.rightLegIds[0];
    if (!groupId || !legId) {
      return;
    }
    this.jumpTo.emit({
      batchId: side === 'left' ? cmp.left.id : cmp.right.id,
      groupId,
      legId,
    });
  }

  jumpInvoice(cmp: BatchComparison, g: GroupComparison, inv: InvoiceDiff, side: 'left' | 'right'): void {
    const groupId = side === 'left' ? g.leftGroupId : g.rightGroupId;
    if (!groupId) {
      return;
    }
    this.jumpTo.emit({
      batchId: side === 'left' ? cmp.left.id : cmp.right.id,
      groupId,
      claimId: inv.claimId,
    });
  }

  presenceText(p: ComparePresence): string {
    return { BOTH: '两侧都有', LEFT_ONLY: '仅左批次', RIGHT_ONLY: '仅右批次' }[p];
  }

  stateText(s: InvoiceState): string {
    return { INCLUDED: '纳入', EXCLUDED: '排除', ABSENT: '未出现' }[s];
  }

  legStateText(l: LegDiff): string {
    if (l.presence === 'LEFT_ONLY') {
      return '右批次已消失';
    }
    if (l.presence === 'RIGHT_ONLY') {
      return '右批次新增';
    }
    return l.diff !== 0 ? '金额变化' : '不变';
  }

  statusText(s: string): string {
    return { SIMULATED: '试算', CONFIRMED: '已确认', PAID_SIMULATED: '模拟已付' }[s] ?? s;
  }

  money(v: number): string {
    return (v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  signed(v: number): string {
    const n = v ?? 0;
    const s = Math.abs(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return (n > 0 ? '+' : n < 0 ? '−' : '') + s;
  }
}
