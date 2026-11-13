import type { StockPositionsResponse, StockTrade } from '../api/types'

/**
 * FEAT-011 scene S (fictional tickers), exactly as the backend returns it.
 * S1 转入 IXQZ 100 / 750.00 (2026-10-01), S2 买入 IXQZ 200 / 1,500.65, S3 买入 XYZ 300 / 1,000.00,
 * S4 卖出 IXQZ 150 / 1,200.00, S5 卖出 XYZ 100 / 400.00.
 */
export function stockTrade(partial: Partial<StockTrade> & Pick<StockTrade, 'id'>): StockTrade {
  return {
    type: 'BUY',
    symbol: 'IXQZ',
    shares: 1,
    amount: '0.00',
    cashChange: '0.00',
    pricePerShare: '0.0000',
    tradedAt: '2026-11-03T15:00:00Z',
    tradedAtLocal: '2026-11-03T10:00:00',
    tradeDate: null,
    note: null,
    ledgerEntryId: partial.id + 1,
    createdAt: '2026-11-13T15:00:00Z',
    hasAuditLog: false,
    soldCost: null,
    realizedPnl: null,
    soldLots: null,
    assignmentTradeId: null,
    editable: true,
    deletable: true,
    ...partial,
  }
}

export const S1 = stockTrade({
  id: 1,
  type: 'TRANSFER_IN',
  shares: 100,
  amount: '750.00',
  cashChange: '0.00',
  pricePerShare: '7.5000',
  tradedAt: '2026-10-01T04:00:00Z',
  tradedAtLocal: '2026-10-01T00:00:00',
  tradeDate: '2026-10-01',
  ledgerEntryId: null,
  note: '上线前持有',
})
export const S2 = stockTrade({
  id: 2,
  shares: 200,
  amount: '1500.65',
  cashChange: '-1500.65',
  pricePerShare: '7.5033',
})
export const S3 = stockTrade({
  id: 3,
  symbol: 'XYZ',
  shares: 300,
  amount: '1000.00',
  cashChange: '-1000.00',
  pricePerShare: '3.3333',
  tradedAt: '2026-11-04T15:00:00Z',
  tradedAtLocal: '2026-11-04T10:00:00',
})
export const S4 = stockTrade({
  id: 4,
  type: 'SELL',
  shares: 150,
  amount: '1200.00',
  cashChange: '1200.00',
  pricePerShare: '8.0000',
  tradedAt: '2026-11-05T15:00:00Z',
  tradedAtLocal: '2026-11-05T10:00:00',
  soldCost: '1125.16',
  realizedPnl: '74.84',
  soldLots: [
    {
      tradeId: 1,
      type: 'TRANSFER_IN',
      tradedAt: '2026-10-01T04:00:00Z',
      tradedAtLocal: '2026-10-01T00:00:00',
      tradeDate: '2026-10-01',
      shares: 100,
      cost: '750.00',
    },
    {
      tradeId: 2,
      type: 'BUY',
      tradedAt: '2026-11-03T15:00:00Z',
      tradedAtLocal: '2026-11-03T10:00:00',
      tradeDate: null,
      shares: 50,
      cost: '375.16',
    },
  ],
})
export const S5 = stockTrade({
  id: 5,
  type: 'SELL',
  symbol: 'XYZ',
  shares: 100,
  amount: '400.00',
  cashChange: '400.00',
  pricePerShare: '4.0000',
  tradedAt: '2026-11-06T15:00:00Z',
  tradedAtLocal: '2026-11-06T10:00:00',
  soldCost: '333.33',
  realizedPnl: '66.67',
  soldLots: [
    {
      tradeId: 3,
      type: 'BUY',
      tradedAt: '2026-11-04T15:00:00Z',
      tradedAtLocal: '2026-11-04T10:00:00',
      tradeDate: null,
      shares: 100,
      cost: '333.33',
    },
  ],
})

/** Backend order: newest first. */
export const SCENE_S: StockTrade[] = [S5, S4, S3, S2, S1]

export const SCENE_S_POSITIONS: StockPositionsResponse = {
  positionCount: 2,
  positions: [
    {
      symbol: 'IXQZ',
      shares: 150,
      totalCost: '1125.49',
      averageCost: '7.5033',
      lotCount: 1,
      lots: [
        {
          tradeId: 2,
          type: 'BUY',
          tradedAt: '2026-11-03T15:00:00Z',
          tradedAtLocal: '2026-11-03T10:00:00',
          tradeDate: null,
          originalShares: 200,
          remainingShares: 150,
          originalCost: '1500.65',
          remainingCost: '1125.49',
          note: null,
        },
      ],
    },
    {
      symbol: 'XYZ',
      shares: 200,
      totalCost: '666.67',
      averageCost: '3.3334',
      lotCount: 1,
      lots: [
        {
          tradeId: 3,
          type: 'BUY',
          tradedAt: '2026-11-04T15:00:00Z',
          tradedAtLocal: '2026-11-04T10:00:00',
          tradeDate: null,
          originalShares: 300,
          remainingShares: 200,
          originalCost: '1000.00',
          remainingCost: '666.67',
          note: '分批买入',
        },
      ],
    },
  ],
}
