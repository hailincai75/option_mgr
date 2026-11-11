import { apiLedger, apiTransfer, CashPage, expect, MINUS, nyLocal, test } from '../support/fixtures'
import { apiGetTrade, apiTrade, apiTradeCount, TradeForm } from '../support/trade'

/**
 * FEAT-001 录入开仓 E2E. The backend runs on the real clock (2026-11-11 at the time of writing), so the two
 * scenarios whose acceptance times fall on 2026-11-11 (AC7's second trade, AC17) are shifted one day earlier
 * here; the exact dates are covered by the fixed-clock backend system tests.
 * "Given" states are seeded via API; every "When/Then" goes through the page.
 */

const DEP = '2026-11-02T10:00'
const AAPL_200P = TradeForm.base({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35' })

test.describe('FEAT-001 录入开仓', () => {
  test('入口与页面：导航进入，表单上方显示现金汇总，净额标签随方向切换，成交时间注明美东时间', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '30000.00', DEP)
    await page.goto('/')
    await page.getByTestId('nav-open-trade').click()
    await expect(page).toHaveURL(/\/trades\/new$/)
    await new CashPage(page).expectSummary('30,000.00', '0.00', '30,000.00')
    await expect(trade.netAmountLabel).toHaveText('净额（含手续费）')
    await trade.actionSto.check()
    await expect(trade.netAmountLabel).toHaveText('实收净额（已扣手续费）')
    await trade.actionBto.check()
    await expect(trade.netAmountLabel).toHaveText('实付总额（含手续费）')
    await expect(page.getByText('美东时间').first()).toBeVisible()
    await expect(trade.multiplier).toHaveValue('100')
  })

  test('AC1+AC16 STO put 成功：确认信息、汇总无需刷新即更新、表单清空；资金页流水为期权开仓 +349.35 冻结 +20,000.00', async ({
    page,
    trade,
  }) => {
    await apiTransfer('DEPOSIT', '30000.00', DEP)
    await trade.open()
    const cash = new CashPage(page)
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })

    await trade.submitAndExpectSaved({ ...AAPL_200P, note: '第一笔 CSP' })
    await expect(trade.confirm('contract')).toHaveText('AAPL 2026-12-18 200 P')
    await expect(trade.confirm('action')).toHaveText('STO（卖出开仓）')
    await expect(trade.confirm('contracts')).toHaveText('1 张')
    await expect(trade.confirm('multiplier')).toHaveCount(0)
    await expect(trade.confirm('net-amount')).toHaveText('349.35')
    await expect(trade.confirm('time')).toHaveText('2026-11-10 10:30（美东）')
    await expect(trade.confirm('cash-change')).toHaveText('+349.35')
    await expect(trade.confirm('frozen-change')).toHaveText('+20,000.00')
    await cash.expectSummary('30,349.35', '20,000.00', '10,349.35')
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)

    // form cleared for the next entry
    await expect(trade.symbol).toHaveValue('')
    await expect(trade.netAmount).toHaveValue('')
    await expect(trade.multiplier).toHaveValue('100')
    await expect(trade.actionSto).not.toBeChecked()

    // stored as UTC
    const ledger = await apiLedger()
    expect(ledger.items[0]?.occurredAt).toBe('2026-11-10T15:30:00Z')

    // ledger on the cash page
    await page.getByTestId('nav-cash').click()
    await cash.expectRows([
      {
        time: '2026-11-10 10:30',
        type: '期权开仓',
        amount: '+349.35',
        frozen: '+20,000.00',
        balance: '30,349.35',
        note: 'STO 1 张 AAPL 2026-12-18 200 P — 第一笔 CSP',
      },
      { type: '入金', amount: '+30,000.00', frozen: '—', balance: '30,000.00' },
    ])
  })

  test('AC2 多张 + 小数行权价：5,048.70 / 2,500.00 / 2,548.70', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '5000.00', DEP)
    await trade.open()
    await trade.submitAndExpectSaved(
      TradeForm.base({ action: 'STO', symbol: 'F', strike: '12.5', type: 'PUT', contracts: '2', netAmount: '48.70' }),
    )
    await expect(trade.confirm('contract')).toHaveText('F 2026-12-18 12.5 P')
    await expect(trade.confirm('contracts')).toHaveText('2 张')
    await new CashPage(page).expectSummary('5,048.70', '2,500.00', '2,548.70')
  })

  test('AC3 BTO 成功：29,487.35 / 0.00 / 29,487.35，流水 −512.65', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '30000.00', DEP)
    await trade.open()
    await trade.submitAndExpectSaved(TradeForm.base({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '512.65' }))
    await expect(trade.confirm('cash-change')).toHaveText(`${MINUS}512.65`)
    // FEAT-004 changed the confirmation: a zero frozen change is shown as — (same rule as the ledger)
    await expect(trade.confirm('frozen-change')).toHaveText('—')
    const cash = new CashPage(page)
    await cash.expectSummary('29,487.35', '0.00', '29,487.35')
    await page.getByTestId('nav-cash').click()
    await cash.expectRows([
      { type: '期权开仓', amount: `${MINUS}512.65`, frozen: '—', balance: '29,487.35', note: 'BTO 1 张 AAPL 2026-12-18 210 C' },
      { amount: '+30,000.00', balance: '30,000.00' },
    ])
  })

  test('AC4+AC16 BTO 可用不足：显示原因、全部输入保留、汇总不变；改净额 500.00 成功 → 可用 0.00', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '500.00', DEP)
    await trade.open()
    const input = TradeForm.base({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '512.65', note: '测试保留' })
    await trade.submitAndExpectError(input, 'CASH_INSUFFICIENT', '可用现金不足：需要 512.65，可用 500.00')
    expect(await trade.values()).toEqual({
      sto: false,
      bto: true,
      symbol: 'AAPL',
      expiration: '2026-12-18',
      strike: '210',
      call: true,
      put: false,
      contracts: '1',
      netAmount: '512.65',
      time: '2026-11-10T10:30',
      multiplier: '100',
      note: '测试保留',
    })
    const cash = new CashPage(page)
    await cash.expectSummary('500.00', '0.00', '500.00')
    expect(await apiTradeCount()).toBe(0)
    expect((await apiLedger()).totalItems).toBe(1)

    await trade.netAmount.fill('500.00')
    await trade.submit.click()
    await expect(trade.confirmation).toBeVisible()
    await expect(trade.error).toHaveCount(0)
    await cash.expectSummary('0.00', '0.00', '0.00')
  })

  test('AC5 冻结现金不能用于买入：需要 900.00，可用 849.35；849.35 成功 → 20,000 / 20,000 / 0', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '20500.00', DEP)
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-10T10:30' })
    await trade.open()
    const cash = new CashPage(page)
    await cash.expectSummary('20,849.35', '20,000.00', '849.35')
    await trade.submitAndExpectError(
      TradeForm.base({ action: 'BTO', symbol: 'MSFT', strike: '450', type: 'CALL', netAmount: '900.00', time: '2026-11-10T11:00' }),
      'CASH_INSUFFICIENT',
      '可用现金不足：需要 900.00，可用 849.35',
    )
    await trade.netAmount.fill('849.35')
    await trade.submit.click()
    await expect(trade.confirmation).toBeVisible()
    await cash.expectSummary('20,000.00', '20,000.00', '0.00')
  })

  test('AC6 STO put 担保不足（权利金不算）：需要 20,000.00，可用 19,999.99', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '19999.99', DEP)
    await trade.open()
    await trade.submitAndExpectError(AAPL_200P, 'CASH_INSUFFICIENT', '可用现金不足以担保卖出 put：需要 20,000.00，可用 19,999.99')
    await new CashPage(page).expectSummary('19,999.99', '0.00', '19,999.99')
    expect(await apiTradeCount()).toBe(0)
  })

  test('AC6 入金恰好 20,000.00：成功 → 20,349.35 / 20,000.00 / 349.35', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '20000.00', DEP)
    await trade.open()
    await trade.submitAndExpectSaved(AAPL_200P)
    await new CashPage(page).expectSummary('20,349.35', '20,000.00', '349.35')
  })

  test('AC7 同一合约多次开仓（第二笔时间提前到 11-10 11:00）：50,709.35 / 40,000.00 / 10,709.35', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '50000.00', DEP)
    await trade.open()
    await trade.submitAndExpectSaved(AAPL_200P)
    await trade.submitAndExpectSaved({ ...AAPL_200P, netAmount: '360.00', time: '2026-11-10T11:00' })
    await new CashPage(page).expectSummary('50,709.35', '40,000.00', '10,709.35')
    expect(await apiTradeCount()).toBe(2)
  })

  test('AC8 多空冲突：空头时 BTO 提示 BTC；不同行权价允许；多头 call 时 STO 提示 STC', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '30000.00', DEP)
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-10T10:30' })
    await trade.open()
    await trade.submitAndExpectError(
      TradeForm.base({ action: 'BTO', strike: '200', type: 'PUT', netAmount: '300.00', time: '2026-11-10T11:00' }),
      'POSITION_DIRECTION_CONFLICT',
      '该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（1 张），不能买入开仓；如要平仓请使用 Buy to Close（BTC）',
    )
    await new CashPage(page).expectSummary('30,349.35', '20,000.00', '10,349.35')
    await trade.strike.fill('205')
    await trade.submit.click()
    await expect(trade.confirmation).toBeVisible()
    await expect(trade.confirm('contract')).toHaveText('AAPL 2026-12-18 205 P')

    await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '512.65', time: '2026-11-10T11:30' })
    await trade.submitAndExpectError(
      TradeForm.base({ action: 'STO', strike: '210', type: 'CALL', netAmount: '100.00', time: '2026-11-10T12:00' }),
      'POSITION_DIRECTION_CONFLICT',
      '该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），不能卖出开仓；如要平仓请使用 Sell to Close（STC）',
    )
  })

  test('AC9 STO call 被拒：需要股票持仓功能（FEAT-011）后才能卖出 call', async ({ trade }) => {
    await apiTransfer('DEPOSIT', '100000.00', DEP)
    await trade.open()
    await trade.submitAndExpectError(
      TradeForm.base({ action: 'STO', strike: '250', type: 'CALL', netAmount: '120.00' }),
      'SELL_CALL_NOT_SUPPORTED',
      '需要股票持仓功能（FEAT-011）后才能卖出 call',
    )
    expect(await apiTradeCount()).toBe(0)
  })

  test('AC10 标的规范化：" aapl " 按 AAPL 冲突被拒；" msft " 保存显示 MSFT', async ({ trade }) => {
    await apiTransfer('DEPOSIT', '70000.00', DEP)
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-10T10:30' })
    await trade.open()
    await trade.submitAndExpectError(
      TradeForm.base({ action: 'BTO', symbol: ' aapl ', strike: '200', type: 'PUT', netAmount: '300.00', time: '2026-11-10T11:00' }),
      'POSITION_DIRECTION_CONFLICT',
      /该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓/,
    )
    await trade.submitAndExpectSaved(
      TradeForm.base({ action: 'STO', symbol: ' msft ', strike: '400', type: 'PUT', netAmount: '500.00', time: '2026-11-10T11:00' }),
    )
    await expect(trade.confirm('contract')).toHaveText('MSFT 2026-12-18 400 P')
  })

  test.describe('AC11 成交时间', () => {
    test.describe('默认当前美东时间（浏览器时区 Asia/Shanghai）', () => {
      test.use({ timezoneId: 'Asia/Shanghai' })
      test('浏览器时钟 UTC 2026-11-10 14:45 → 默认 2026-11-10T09:45', async ({ page, trade }) => {
        await page.clock.setFixedTime(new Date('2026-11-10T14:45:00Z'))
        await trade.open()
        await expect(trade.time).toHaveValue('2026-11-10T09:45')
      })
      test('夏令时：浏览器时钟 UTC 2026-07-15 14:30 → 默认 2026-07-15T10:30', async ({ page, trade }) => {
        await page.clock.setFixedTime(new Date('2026-07-15T14:30:00Z'))
        await trade.open()
        await expect(trade.time).toHaveValue('2026-07-15T10:30')
      })
    })

    test('录入 2026-07-15 10:30（夏令时）存 14:30Z，确认显示 2026-07-15 10:30（美东）；11-10 10:30 存 15:30Z', async ({ trade }) => {
      await apiTransfer('DEPOSIT', '100000.00', '2026-01-02T10:00')
      await trade.open()
      await trade.submitAndExpectSaved(
        TradeForm.base({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '1.00', time: '2026-07-15T10:30' }),
      )
      await expect(trade.confirm('time')).toHaveText('2026-07-15 10:30（美东）')
      await trade.submitAndExpectSaved(
        TradeForm.base({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '1.00', time: '2026-11-10T10:30' }),
      )
      await expect(trade.confirm('time')).toHaveText('2026-11-10 10:30（美东）')
      const items = (await apiLedger()).items.filter((i) => i.sourceType === 'OPTION_OPEN').map((i) => i.occurredAt)
      expect(items).toEqual(['2026-11-10T15:30:00Z', '2026-07-15T14:30:00Z'])
      const first = await apiGetTrade(1)
      expect(first.tradedAtLocal).toBe('2026-07-15T10:30:00')
    })
  })

  test.describe('AC12 字段校验：每项单独提交被拒、不保存，错误显示在对应字段', () => {
    type Field = 'contracts' | 'netAmount' | 'strike' | 'multiplier' | 'symbol' | 'time' | 'expiration' | 'note'
    const cases: { name: string; field: Field; input: Record<string, string>; message?: string | RegExp }[] = [
      { name: '张数 0', field: 'contracts', input: { contracts: '0' } },
      { name: '张数 -1', field: 'contracts', input: { contracts: '-1' } },
      { name: '张数 1.5', field: 'contracts', input: { contracts: '1.5' } },
      { name: '净额 0', field: 'netAmount', input: { netAmount: '0' }, message: '净额必须大于 0' },
      { name: '净额 -10.00', field: 'netAmount', input: { netAmount: '-10.00' } },
      { name: '净额 349.355', field: 'netAmount', input: { netAmount: '349.355' }, message: '净额最多 2 位小数' },
      { name: '行权价 0', field: 'strike', input: { strike: '0' }, message: '行权价必须大于 0' },
      { name: '行权价 -5', field: 'strike', input: { strike: '-5' } },
      { name: '行权价 200.1234', field: 'strike', input: { strike: '200.1234' }, message: '行权价最多 3 位小数' },
      { name: 'multiplier 0', field: 'multiplier', input: { multiplier: '0' } },
      { name: 'multiplier 10.5', field: 'multiplier', input: { multiplier: '10.5' } },
      { name: '标的为空', field: 'symbol', input: { symbol: '' }, message: '标的不能为空' },
      { name: '标的 AA PL', field: 'symbol', input: { symbol: 'AA PL' }, message: '标的只能包含字母、数字和点' },
      { name: '标的 $AAPL', field: 'symbol', input: { symbol: '$AAPL' }, message: '标的只能包含字母、数字和点' },
      { name: '标的 11 个字符', field: 'symbol', input: { symbol: 'ABCDEFGHIJK' }, message: '标的最多 10 个字符' },
      { name: '成交时间晚于当前', field: 'time', input: { time: 'FUTURE' }, message: '成交时间不能晚于当前时间' },
      {
        name: '到期日早于成交日',
        field: 'expiration',
        input: { expiration: '2026-11-09', time: '2026-11-10T10:30' },
        message: '到期日不能早于成交日（2026-11-10）',
      },
      { name: '备注 501 字符', field: 'note', input: { note: 'x'.repeat(501) }, message: '备注最多 500 个字符' },
      { name: '行权价超上限 10000000（AC19）', field: 'strike', input: { strike: '10000000' }, message: '行权价过大' },
      { name: '张数超上限 100001（AC19）', field: 'contracts', input: { contracts: '100001' }, message: '张数过大' },
      { name: 'multiplier 超上限 10001（AC19）', field: 'multiplier', input: { multiplier: '10001' }, message: 'multiplier 过大' },
      {
        name: '净额超上限 10,000,000,000,000.00（AC19）',
        field: 'netAmount',
        input: { netAmount: '10000000000000.00' },
        message: '净额过大',
      },
    ]
    for (const c of cases) {
      test(c.name, async ({ page, trade }) => {
        await apiTransfer('DEPOSIT', '100000.00', DEP)
        const posts: string[] = []
        page.on('request', (r) => {
          if (r.method() === 'POST' && r.url().includes('/api/option-trades')) posts.push(r.url())
        })
        await trade.open()
        const input: Record<string, string> = { ...c.input }
        if (input.time === 'FUTURE') input.time = nyLocal(new Date(Date.now() + 3600_000))
        await trade.fill(TradeForm.base({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '1.00', ...input }))
        await trade.submit.click()
        const err = trade.fieldError(trade[c.field])
        await expect(err).toHaveCount(1)
        if (c.message) await expect(err).toHaveText(c.message)
        else await expect(err).not.toHaveText('')
        await expect(trade.allFieldErrors()).toHaveCount(1)
        await page.waitForTimeout(300)
        expect(posts).toHaveLength(0)
        expect(await apiTradeCount()).toBe(0)
        await new CashPage(page).expectSummary('100,000.00', '0.00', '100,000.00')
      })
    }

    test('未选方向、未选 Call/Put：错误在对应字段', async ({ page, trade }) => {
      await trade.open()
      await trade.fill(TradeForm.base({ strike: '210', netAmount: '1.00' }))
      await trade.submit.click()
      await expect(
        page.locator('.ant-form-item').filter({ has: trade.actionBto }).locator('.ant-form-item-explain-error'),
      ).toHaveText('请选择方向')
      await expect(
        page.locator('.ant-form-item').filter({ has: trade.typeCall }).locator('.ant-form-item-explain-error'),
      ).not.toHaveText('')
      expect(await apiTradeCount()).toBe(0)
    })

    test('0DTE：到期日等于成交日（今天）允许保存', async ({ trade }) => {
      await apiTransfer('DEPOSIT', '100000.00', DEP)
      const time = nyLocal(new Date(Date.now() - 3600_000))
      const today = time.slice(0, 10)
      await trade.open()
      await trade.submitAndExpectSaved(
        TradeForm.base({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '1.00', time, expiration: today }),
      )
      await expect(trade.confirm('contract')).toHaveText(`AAPL ${today} 210 C`)
    })

    test('后端字段错误（浏览器时钟偏快）显示在成交时间字段下并保留输入', async ({ page, trade }) => {
      await apiTransfer('DEPOSIT', '100000.00', DEP)
      await page.clock.setFixedTime(new Date(Date.now() + 2 * 86400_000))
      await trade.open()
      const future = nyLocal(new Date(Date.now() + 86400_000))
      const input = TradeForm.base({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '1.00', time: future })
      await trade.fill(input)
      await trade.submit.click()
      await expect(trade.fieldError(trade.time)).toHaveText('成交时间不能晚于当前时间')
      await expect(trade.time).toHaveValue(future)
      await expect(trade.symbol).toHaveValue('AAPL')
      expect(await apiTradeCount()).toBe(0)
    })
  })

  test('AC13 补录 BTO：11-05 变 −500.00 被拒；800.00 成功 → 200.00；早于首笔入金被拒 可用 0.00', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '10000.00', '2026-11-02T10:00')
    await apiTransfer('WITHDRAWAL', '9000.00', '2026-11-05T10:00')
    await trade.open()
    const input = TradeForm.base({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '1500.00', time: '2026-11-03T10:00' })
    await trade.submitAndExpectError(
      input,
      'CASH_INSUFFICIENT',
      `可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 ${MINUS}500.00`,
    )
    await trade.netAmount.fill('800.00')
    await trade.submit.click()
    await expect(trade.confirmation).toBeVisible()
    await new CashPage(page).expectSummary('200.00', '0.00', '200.00')
    await trade.submitAndExpectError(
      { ...input, netAmount: '100.00', time: '2026-11-01T10:00' },
      'CASH_INSUFFICIENT',
      '可用现金不足：需要 100.00，可用 0.00',
    )
  })

  test('AC14 补录 STO put 使之后出金时可用变为 −150.65 → 拒绝', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '25000.00', '2026-11-02T10:00')
    await apiTransfer('WITHDRAWAL', '5500.00', '2026-11-05T10:00')
    await trade.open()
    await trade.submitAndExpectError(
      { ...AAPL_200P, time: '2026-11-03T10:00' },
      'CASH_INSUFFICIENT',
      `可用现金不足以担保卖出 put：需要 20,000.00；2026-11-05 10:00 时可用现金将变为 ${MINUS}150.65`,
    )
    await new CashPage(page).expectSummary('19,500.00', '0.00', '19,500.00')
  })

  test('AC14 出金 4,000.00 场景：补录成功 → 21,349.35 / 20,000.00 / 1,349.35', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '25000.00', '2026-11-02T10:00')
    await apiTransfer('WITHDRAWAL', '4000.00', '2026-11-05T10:00')
    await trade.open()
    await trade.submitAndExpectSaved({ ...AAPL_200P, time: '2026-11-03T10:00' })
    await new CashPage(page).expectSummary('21,349.35', '20,000.00', '1,349.35')
  })

  test('FEAT-007 AC11 补测：资金页出金不能动用冻结现金（冻结 20,000 后出金 5,500 被拒）', async ({ cash }) => {
    await apiTransfer('DEPOSIT', '25000.00', '2026-11-02T10:00')
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:00' })
    await cash.open()
    await cash.expectSummary('25,349.35', '20,000.00', '5,349.35')
    await cash.fill({ type: 'WITHDRAWAL', amount: '5500.00', time: '2026-11-05T10:00' })
    await cash.submit.click()
    await expect(cash.submitError).toHaveText('可用现金不足：需要 5,500.00，可用 5,349.35')
    await cash.expectSummary('25,349.35', '20,000.00', '5,349.35')
  })

  test('AC15 multiplier 10：冻结 2,000.00，确认注明 multiplier；同合约 multiplier 100 被拒', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '5000.00', DEP)
    await trade.open()
    const xyz = TradeForm.base({ action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '34.35', multiplier: '10' })
    await trade.submitAndExpectSaved(xyz)
    await expect(trade.confirm('multiplier')).toContainText('multiplier 10')
    await expect(trade.confirm('frozen-change')).toHaveText('+2,000.00')
    await new CashPage(page).expectSummary('5,034.35', '2,000.00', '3,034.35')
    await trade.submitAndExpectError(
      { ...xyz, multiplier: '100', netAmount: '300.00', time: '2026-11-10T11:00' },
      'MULTIPLIER_MISMATCH',
      '该合约（XYZ 2026-12-18 200 P）已有未平仓持仓，multiplier 为 10，新开仓的 multiplier 必须相同',
    )
  })

  test('AC17 补录较早 BTO 使之后的 STO 冲突（日期整体提前一天）；同方向补录成功', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '50000.00', DEP)
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-10T10:00' })
    await trade.open()
    const cash = new CashPage(page)
    await cash.expectSummary('50,349.35', '20,000.00', '30,349.35')
    await trade.submitAndExpectError(
      TradeForm.base({ action: 'BTO', strike: '200', type: 'PUT', netAmount: '300.00', time: '2026-11-09T10:00' }),
      'POSITION_DIRECTION_CONFLICT',
      '补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-10 10:00 的 STO 将与多头持仓（1 张）冲突；一个合约任一时刻只能纯多或纯空',
    )
    await cash.expectSummary('50,349.35', '20,000.00', '30,349.35')
    await trade.submitAndExpectSaved({ ...AAPL_200P, netAmount: '340.00', time: '2026-11-09T10:00' })
    await cash.expectSummary('50,689.35', '40,000.00', '10,689.35')
    expect(await apiTradeCount()).toBe(2)
  })

  test('AC17 补录较早不同 multiplier → MULTIPLIER_MISMATCH；multiplier 10 补录成功', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '30000.00', DEP)
    await apiTrade({ action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '34.35', time: '2026-11-10T10:00', multiplier: 10 })
    await trade.open()
    const cash = new CashPage(page)
    await cash.expectSummary('30,034.35', '2,000.00', '28,034.35')
    const input = TradeForm.base({
      action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '300.00', time: '2026-11-09T10:00', multiplier: '100',
    })
    await trade.submitAndExpectError(
      input,
      'MULTIPLIER_MISMATCH',
      '补录后该合约（XYZ 2026-12-18 200 P）在 2026-11-10 10:00 的交易 multiplier 为 10，与当时持仓的 multiplier 100 不一致',
    )
    await cash.expectSummary('30,034.35', '2,000.00', '28,034.35')
    await trade.submitAndExpectSaved({ ...input, multiplier: '10', netAmount: '30.00' })
    await cash.expectSummary('30,064.35', '4,000.00', '26,064.35')
  })

  test('AC18 担保 12.345 → 12.35：100.50 / 12.35 / 88.15', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '100.00', DEP)
    await trade.open()
    await trade.submitAndExpectSaved(
      TradeForm.base({ action: 'STO', symbol: 'F', strike: '12.345', type: 'PUT', netAmount: '0.50', multiplier: '1' }),
    )
    await expect(trade.confirm('frozen-change')).toHaveText('+12.35')
    await expect(trade.confirm('contract')).toHaveText('F 2026-12-18 12.345 P')
    await new CashPage(page).expectSummary('100.50', '12.35', '88.15')
  })

  test('AC18 入金 12.34：需要 12.35，可用 12.34 被拒', async ({ trade }) => {
    await apiTransfer('DEPOSIT', '12.34', DEP)
    await trade.open()
    await trade.submitAndExpectError(
      TradeForm.base({ action: 'STO', symbol: 'F', strike: '12.345', type: 'PUT', netAmount: '0.50', multiplier: '1' }),
      'CASH_INSUFFICIENT',
      '可用现金不足以担保卖出 put：需要 12.35，可用 12.34',
    )
  })

  test('AC19 等于上限允许：行权价 9999999.999；100,000 张 × multiplier 10,000 → 99,998.00', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '100000.00', DEP)
    await trade.open()
    await trade.submitAndExpectSaved(
      TradeForm.base({ action: 'BTO', strike: '9999999.999', type: 'CALL', netAmount: '1.00' }),
    )
    await expect(trade.confirm('contract')).toHaveText('AAPL 2026-12-18 9999999.999 C')
    await trade.submitAndExpectSaved(
      TradeForm.base({
        action: 'BTO', symbol: 'MSFT', strike: '300', type: 'CALL', contracts: '100000', multiplier: '10000', netAmount: '1.00',
      }),
    )
    await expect(trade.confirm('contracts')).toContainText('100000 张')
    await new CashPage(page).expectSummary('99,998.00', '0.00', '99,998.00')
  })

  test('清空按钮：清掉输入并恢复默认 multiplier 100', async ({ trade }) => {
    await trade.open()
    await trade.fill(TradeForm.base({ action: 'STO', strike: '200', type: 'PUT', netAmount: '1.00', multiplier: '10', note: 'x' }))
    await trade.clear.click()
    await expect(trade.symbol).toHaveValue('')
    await expect(trade.strike).toHaveValue('')
    await expect(trade.multiplier).toHaveValue('100')
    await expect(trade.note).toHaveValue('')
    await expect(trade.actionSto).not.toBeChecked()
  })
})
