import { unrealizedDisplay } from './unrealizedLabels'

describe('unrealizedDisplay (FEAT-017)', () => {
  it('formats OK rows with an explicit market value sign', () => {
    expect(
      unrealizedDisplay(
        {
          marketValue: '370.00',
          unrealizedPnl: '68.70',
          unrealizedPnlPercent: '22.80',
          unrealizedStatus: 'OK',
          unrealizedLabel: null,
        },
      ),
    ).toEqual({
      kind: 'value',
      marketValue: '+370.00',
      pnl: '+68.70',
      percent: '+22.80%',
      sign: 1,
    })
  })

  it('signs stock market values too; zero has no sign; null percent → —', () => {
    expect(
      unrealizedDisplay(
        {
          marketValue: '6110.00',
          unrealizedPnl: '-230.00',
          unrealizedPnlPercent: null,
          unrealizedStatus: 'OK',
          unrealizedLabel: null,
        },
      ),
    ).toEqual({
      kind: 'value',
      marketValue: '+6,110.00',
      pnl: '−230.00',
      percent: '—',
      sign: -1,
    })
  })

  it('shows the backend label when the row cannot be computed', () => {
    expect(
      unrealizedDisplay(
        { unrealizedStatus: 'NO_QUOTE', unrealizedLabel: '无报价', marketValue: null },
      ),
    ).toEqual({ kind: 'label', label: '无报价' })
    expect(
      unrealizedDisplay(
        { unrealizedStatus: 'EXPIRED_PENDING', unrealizedLabel: '已到期，待处理' },
      ),
    ).toEqual({ kind: 'label', label: '已到期，待处理' })
  })

  it('shows — for responses without the FEAT-017 fields', () => {
    expect(unrealizedDisplay({})).toEqual({
      kind: 'value',
      marketValue: '—',
      pnl: '—',
      percent: '—',
      sign: 0,
    })
  })
})

describe('unrealizedDisplay zero values (FEAT-017)', () => {
  it('shows 0.00 without a sign for zero market value and P&L', () => {
    expect(
      unrealizedDisplay({
        marketValue: '0.00',
        unrealizedPnl: '0.00',
        unrealizedPnlPercent: '0.00',
        unrealizedStatus: 'OK',
        unrealizedLabel: null,
      }),
    ).toEqual({ kind: 'value', marketValue: '0.00', pnl: '0.00', percent: '0.00%', sign: 0 })
  })
})
