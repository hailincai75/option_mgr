import { apiLedger, apiTransfer, expect, MINUS, nyLocal, test } from '../support/fixtures'

/**
 * FEAT-007 资金账户 E2E. Every test runs against its own freshly started backend (empty in-memory DB).
 * "Given" states are seeded through the API where the requirement does not ask to see the UI for them;
 * every "When/Then" goes through the page and checks what the user sees.
 */

test.describe('FEAT-007 资金账户', () => {
  test('AC1 空账户：从导航进入资金页，汇总 0.00/0.00/0.00，流水显示“暂无流水”', async ({ page, cash }) => {
    await page.goto('/')
    await page.getByTestId('nav-cash').click()
    await expect(page).toHaveURL(/\/cash$/)
    await cash.expectSummary('0.00', '0.00', '0.00')
    await expect(cash.ledgerEmpty).toHaveText('暂无流水')
    await expect(cash.ledgerRows).toHaveCount(0)
    await expect(cash.ledgerPagination).toHaveCount(0)
  })

  test('AC2+AC3+AC11+AC12 入金、出金，不刷新页面汇总与流水即更新，冻结始终 0.00', async ({ page, cash }) => {
    await cash.open()
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })

    // AC 2
    await cash.save({ type: 'DEPOSIT', amount: '30000.00', time: '2026-11-02T10:00', remark: '初始资金' })
    await cash.expectSummary('30,000.00', '0.00', '30,000.00')
    await cash.expectRows([
      { time: '2026-11-02 10:00', type: '入金', amount: '+30,000.00', balance: '30,000.00', note: '初始资金' },
    ])
    const afterDeposit = await apiLedger()
    expect(afterDeposit.items[0]?.occurredAt).toBe('2026-11-02T15:00:00Z')

    // AC 3
    await cash.save({ type: 'WITHDRAWAL', amount: '2,500.50', time: '2026-11-03T09:30' })
    await cash.expectSummary('27,499.50', '0.00', '27,499.50')
    await cash.expectRows([
      { time: '2026-11-03 09:30', type: '出金', amount: `${MINUS}2,500.50`, balance: '27,499.50', note: '—' },
      { time: '2026-11-02 10:00', type: '入金', amount: '+30,000.00', balance: '30,000.00', note: '初始资金' },
    ])
    await expect(cash.ledgerTotal).toHaveText('共 2 条')

    // AC 12: no reload happened (window state survived)
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
    // after success the form is reset: no type, empty amount/remark
    await expect(cash.amount).toHaveValue('')
    await expect(cash.remark).toHaveValue('')
    await expect(page.locator('[data-testid="cash-transfer-type"] input:checked')).toHaveCount(0)
  })

  test('AC4+AC12 出金超过可用：显示原因、保留输入、汇总流水不变；改为 27,499.50 成功到 0.00', async ({
    page,
    cash,
  }) => {
    await apiTransfer('DEPOSIT', '30000.00', '2026-11-02T10:00', '初始资金')
    await apiTransfer('WITHDRAWAL', '2500.50', '2026-11-03T09:30')
    await cash.open()
    await cash.expectSummary('27,499.50', '0.00', '27,499.50')
    await expect(cash.ledgerRows).toHaveCount(2)
    const rowsBefore = await cash.rows()

    await cash.fill({ type: 'WITHDRAWAL', amount: '27499.51', time: '2026-11-04T10:00', remark: '转回银行' })
    await cash.submit.click()
    await expect(cash.submitError).toHaveText('可用现金不足：需要 27,499.51，可用 27,499.50')

    // AC 12: everything kept
    await expect(page.locator('[data-testid="cash-transfer-type"] input:checked')).toHaveValue('WITHDRAWAL')
    await expect(cash.amount).toHaveValue('27499.51')
    await expect(cash.time).toHaveValue('2026-11-04T10:00')
    await expect(cash.remark).toHaveValue('转回银行')
    // nothing changed
    await cash.expectSummary('27,499.50', '0.00', '27,499.50')
    expect(await cash.rows()).toEqual(rowsBefore)
    expect((await apiLedger()).totalItems).toBe(2)

    // exact amount works and brings everything to 0.00
    await cash.amount.fill('27499.50')
    await cash.submit.click()
    await expect(page.locator('.ant-message').getByText('出金已保存')).toBeVisible()
    await expect(cash.submitError).toHaveCount(0)
    await cash.expectSummary('0.00', '0.00', '0.00')
    await cash.expectRows([
      { time: '2026-11-04 10:00', type: '出金', amount: `${MINUS}27,499.50`, balance: '0.00', note: '转回银行' },
      { amount: `${MINUS}2,500.50`, balance: '27,499.50' },
      { amount: '+30,000.00', balance: '30,000.00' },
    ])
  })

  test('AC5 补录历史出金：之后时点会变负被拒并说明时点；1,000.00 成功到 0.00', async ({ cash }) => {
    await apiTransfer('DEPOSIT', '10000.00', '2026-11-02T10:00')
    await apiTransfer('WITHDRAWAL', '9000.00', '2026-11-05T10:00')
    await cash.open()
    await cash.expectSummary('1,000.00', '0.00', '1,000.00')

    await cash.fill({ type: 'WITHDRAWAL', amount: '1500.00', time: '2026-11-03T10:00' })
    await cash.submit.click()
    await expect(cash.submitError).toHaveText(
      `可用现金不足：需要 1,500.00；2026-11-05 10:00 时可用现金将变为 ${MINUS}500.00`,
    )
    await cash.expectSummary('1,000.00', '0.00', '1,000.00')
    await expect(cash.ledgerRows).toHaveCount(2)

    await cash.amount.fill('1000.00')
    await cash.submit.click()
    await expect(cash.page.locator('.ant-message').getByText('出金已保存')).toBeVisible()
    await cash.expectSummary('0.00', '0.00', '0.00')
    await cash.expectRows([
      { time: '2026-11-05 10:00', type: '出金', amount: `${MINUS}9,000.00`, balance: '0.00' },
      { time: '2026-11-03 10:00', type: '出金', amount: `${MINUS}1,000.00`, balance: '9,000.00' },
      { time: '2026-11-02 10:00', type: '入金', amount: '+10,000.00', balance: '10,000.00' },
    ])
  })

  test('AC5 补录出金早于第一笔入金：被拒，可用 0.00', async ({ cash }) => {
    await apiTransfer('DEPOSIT', '10000.00', '2026-11-02T10:00')
    await apiTransfer('WITHDRAWAL', '9000.00', '2026-11-05T10:00')
    await cash.open()
    await cash.fill({ type: 'WITHDRAWAL', amount: '100.00', time: '2026-11-01T10:00' })
    await cash.submit.click()
    await expect(cash.submitError).toHaveText('可用现金不足：需要 100.00，可用 0.00')
    await cash.expectSummary('1,000.00', '0.00', '1,000.00')
    await expect(cash.ledgerRows).toHaveCount(2)
  })

  test('AC6 补录历史入金：之后那条的余额重新累计', async ({ cash }) => {
    await cash.open()
    await cash.save({ type: 'DEPOSIT', amount: '10000.00', time: '2026-11-05T10:00' })
    await cash.expectRows([{ time: '2026-11-05 10:00', amount: '+10,000.00', balance: '10,000.00' }])

    await cash.save({ type: 'DEPOSIT', amount: '5000.00', time: '2026-11-02T10:00' })
    await cash.expectSummary('15,000.00', '0.00', '15,000.00')
    await cash.expectRows([
      { time: '2026-11-05 10:00', type: '入金', amount: '+10,000.00', balance: '15,000.00' },
      { time: '2026-11-02 10:00', type: '入金', amount: '+5,000.00', balance: '5,000.00' },
    ])
  })

  test('AC7 同一时间多条按录入顺序：出金在上余额 0.00，入金在下余额 1,000.00', async ({ cash }) => {
    await cash.open()
    await cash.save({ type: 'DEPOSIT', amount: '1000.00', time: '2026-11-03T10:00' })
    await cash.save({ type: 'WITHDRAWAL', amount: '1000.00', time: '2026-11-03T10:00' })
    await cash.expectSummary('0.00', '0.00', '0.00')
    await cash.expectRows([
      { time: '2026-11-03 10:00', type: '出金', amount: `${MINUS}1,000.00`, balance: '0.00' },
      { time: '2026-11-03 10:00', type: '入金', amount: '+1,000.00', balance: '1,000.00' },
    ])
  })

  test('AC7 空库先录出金被拒；再录同一时间入金，出金不会被“补上”', async ({ cash }) => {
    await cash.open()
    await cash.fill({ type: 'WITHDRAWAL', amount: '1000.00', time: '2026-11-03T10:00' })
    await cash.submit.click()
    await expect(cash.submitError).toHaveText('可用现金不足：需要 1,000.00，可用 0.00')
    await expect(cash.ledgerEmpty).toBeVisible()

    await cash.typeDeposit.click()
    await cash.submit.click()
    await expect(cash.page.locator('.ant-message').getByText('入金已保存')).toBeVisible()
    await cash.expectSummary('1,000.00', '0.00', '1,000.00')
    await cash.expectRows([{ time: '2026-11-03 10:00', type: '入金', amount: '+1,000.00', balance: '1,000.00' }])
  })

  test.describe('AC8 默认时间为当前美东时间（浏览器时区为 Asia/Shanghai）', () => {
    test.use({ timezoneId: 'Asia/Shanghai' })

    test('浏览器时钟固定为 UTC 2026-11-10 14:45 → 默认 2026-11-10 09:45，旁注“美东时间”', async ({
      page,
      cash,
    }) => {
      await page.clock.setFixedTime(new Date('2026-11-10T14:45:00Z'))
      await cash.open()
      await expect(cash.time).toHaveValue('2026-11-10T09:45')
      await expect(page.getByText('美东时间').first()).toBeVisible()
    })

    test('真实时钟：默认值等于当前美东时间（允许跨一分钟）', async ({ cash }) => {
      const before = nyLocal(new Date())
      await cash.open()
      const value = await cash.time.inputValue()
      const after = nyLocal(new Date())
      expect([before, after]).toContain(value)
    })
  })

  test('AC8 夏令时/标准时间换算：07-15 10:30 → 14:30Z，11-02 10:00 → 15:00Z，列表按美东显示', async ({ cash }) => {
    await cash.open()
    await cash.save({ type: 'DEPOSIT', amount: '1.00', time: '2026-07-15T10:30' })
    await cash.save({ type: 'DEPOSIT', amount: '2.00', time: '2026-11-02T10:00' })
    await cash.expectRows([
      { time: '2026-11-02 10:00', amount: '+2.00', balance: '3.00' },
      { time: '2026-07-15 10:30', amount: '+1.00', balance: '1.00' },
    ])
    const l = await apiLedger()
    expect(l.items.map((i) => i.occurredAt)).toEqual(['2026-11-02T15:00:00Z', '2026-07-15T14:30:00Z'])
  })

  test.describe('AC9 字段校验：每项单独提交都被拒、不保存，错误显示在对应字段下', () => {
    type Field = 'amount' | 'time' | 'remark' | 'type'
    const cases: { name: string; field: Field; input: Record<string, string>; message?: RegExp }[] = [
      { name: '金额为空', field: 'amount', input: { amount: '' } },
      { name: '金额 0', field: 'amount', input: { amount: '0' } },
      { name: '金额 0.00', field: 'amount', input: { amount: '0.00' } },
      { name: '金额 -100.00', field: 'amount', input: { amount: '-100.00' } },
      { name: '金额 100.001', field: 'amount', input: { amount: '100.001' }, message: /2 位小数/ },
      { name: '金额 abc', field: 'amount', input: { amount: 'abc' } },
      { name: '时间晚于当前', field: 'time', input: { time: 'FUTURE' }, message: /晚于当前/ },
      { name: '时间为空', field: 'time', input: { time: '' } },
      { name: '未选择类型', field: 'type', input: { type: '' } },
      { name: '备注 501 字符', field: 'remark', input: { remark: 'x'.repeat(501) }, message: /500/ },
    ]
    for (const c of cases) {
      test(c.name, async ({ page, cash }) => {
        const posts: string[] = []
        page.on('request', (r) => {
          if (r.method() === 'POST' && r.url().includes('/api/cash/transfers')) posts.push(r.url())
        })
        await cash.open()
        const values: Record<string, string> = {
          type: 'DEPOSIT',
          amount: '100.00',
          time: '2026-11-02T10:00',
          remark: '',
          ...c.input,
        }
        if (values.time === 'FUTURE') values.time = nyLocal(new Date(Date.now() + 60 * 60 * 1000))
        if (values.type) await cash.chooseType(values.type as 'DEPOSIT')
        await cash.amount.fill(values.amount ?? '')
        await cash.time.fill(values.time ?? '')
        await cash.remark.fill(values.remark ?? '')
        await cash.submit.click()

        const locators = { type: 'type', amount: cash.amount, time: cash.time, remark: cash.remark } as const
        const err = cash.fieldError(locators[c.field])
        await expect(err).toHaveCount(1)
        await expect(err).not.toHaveText('')
        if (c.message) await expect(err).toHaveText(c.message)
        for (const other of Object.keys(locators) as Field[]) {
          if (other === c.field) continue
          await expect(cash.fieldError(locators[other])).toHaveCount(0)
        }
        // nothing sent, nothing saved
        await page.waitForTimeout(300)
        expect(posts).toHaveLength(0)
        expect((await apiLedger()).totalItems).toBe(0)
        await cash.expectSummary('0.00', '0.00', '0.00')
        await expect(cash.ledgerEmpty).toBeVisible()
      })
    }

    test('备注恰好 500 字符允许保存', async ({ cash }) => {
      await cash.open()
      const remark = '冲'.repeat(500)
      await cash.save({ type: 'DEPOSIT', amount: '100.00', time: '2026-11-02T10:00', remark })
      await cash.expectRows([{ amount: '+100.00', balance: '100.00', note: remark }])
    })

    test('后端字段错误（浏览器时钟偏快、时间晚于服务器当前）显示在时间字段下并保留输入', async ({ page, cash }) => {
      // Browser clock 2 days ahead: the client check passes, the backend (real clock) rejects.
      await page.clock.setFixedTime(new Date(Date.now() + 2 * 24 * 3600 * 1000))
      await cash.open()
      const future = nyLocal(new Date(Date.now() + 24 * 3600 * 1000))
      await cash.fill({ type: 'DEPOSIT', amount: '100.00', time: future, remark: '未来' })
      await cash.submit.click()
      await expect(cash.fieldError(cash.time)).toHaveText('时间不能晚于当前时间')
      await expect(cash.amount).toHaveValue('100.00')
      await expect(cash.time).toHaveValue(future)
      await expect(cash.remark).toHaveValue('未来')
      expect((await apiLedger()).totalItems).toBe(0)
    })
  })

  test('AC10 精度：0.10 + 0.20 汇总正好显示 0.30', async ({ cash }) => {
    await cash.open()
    await cash.save({ type: 'DEPOSIT', amount: '0.10', time: '2026-11-02T10:00' })
    await cash.save({ type: 'DEPOSIT', amount: '0.20', time: '2026-11-02T10:01' })
    await cash.expectSummary('0.30', '0.00', '0.30')
    await cash.expectRows([
      { amount: '+0.20', balance: '0.30' },
      { amount: '+0.10', balance: '0.10' },
    ])
  })

  test('AC10 入金 1,234,567.89（输入带千分位）：汇总与流水都显示 1,234,567.89', async ({ cash }) => {
    await cash.open()
    await cash.save({ type: 'DEPOSIT', amount: '1,234,567.89', time: '2026-11-02T10:00' })
    await cash.expectSummary('1,234,567.89', '0.00', '1,234,567.89')
    await cash.expectRows([{ amount: '+1,234,567.89', balance: '1,234,567.89' }])
  })

  test('AC13 60 条入金：倒序分页，首条余额 60.00，末页末条 2026-09-01 余额 1.00，翻页可见全部', async ({ cash }) => {
    const start = Date.UTC(2026, 8, 1)
    for (let i = 0; i < 60; i++) {
      const d = new Date(start + i * 86400000).toISOString().slice(0, 10)
      await apiTransfer('DEPOSIT', '1.00', `${d}T10:00`)
    }
    await cash.open()
    await cash.expectSummary('60.00', '0.00', '60.00')
    await expect(cash.ledgerTotal).toHaveText('共 60 条')
    await expect(cash.ledgerRows).toHaveCount(20)
    expect((await cash.rows())[0]).toMatchObject({
      time: '2026-10-30 10:00',
      type: '入金',
      amount: '+1.00',
      balance: '60.00',
    })

    const pager = cash.ledgerPagination
    const seen: string[] = []
    for (let p = 1; p <= 3; p++) {
      if (p > 1) await pager.locator(`.ant-pagination-item-${p}`).click()
      await expect(pager.locator('.ant-pagination-item-active')).toHaveText(`${p}`)
      await expect.poll(async () => (await cash.rows())[0]?.balance).toBe(`${60 - (p - 1) * 20}.00`)
      const rows = await cash.rows()
      expect(rows).toHaveLength(20)
      rows.forEach((r, i) => expect(r.balance).toBe(`${60 - (p - 1) * 20 - i}.00`))
      seen.push(...rows.map((r) => r.time))
    }
    expect(new Set(seen).size).toBe(60)
    expect(seen).toEqual([...seen].sort().reverse())
    expect((await cash.rows())[19]).toMatchObject({ time: '2026-09-01 10:00', balance: '1.00' })
    await expect(pager.locator('.ant-pagination-next')).toHaveClass(/ant-pagination-disabled/)

    // Previous page
    await pager.locator('.ant-pagination-prev').click()
    await expect(pager.locator('.ant-pagination-item-active')).toHaveText('2')
    await expect.poll(async () => (await cash.rows())[0]?.balance).toBe('40.00')

    // saving a new entry jumps back to page 1 and shows it on top
    await cash.save({ type: 'DEPOSIT', amount: '1.00', time: '2026-11-02T10:00' })
    await expect(pager.locator('.ant-pagination-item-active')).toHaveText('1')
    await expect(cash.ledgerTotal).toHaveText('共 61 条')
    await expect.poll(async () => (await cash.rows())[0]).toMatchObject({ time: '2026-11-02 10:00', balance: '61.00' })
  })

  test('后端不可用：提交显示错误并保留输入；刷新后汇总/流水显示错误与重试，后端恢复后重试成功', async ({
    page,
    cash,
    backend,
  }) => {
    await cash.open()
    await cash.expectSummary('0.00', '0.00', '0.00')
    await backend.stop()

    await cash.fill({ type: 'DEPOSIT', amount: '100.00', time: '2026-11-02T10:00', remark: '断网' })
    await cash.submit.click()
    await expect(cash.submitError).toBeVisible()
    await expect(cash.submitError).not.toHaveText('')
    await expect(cash.amount).toHaveValue('100.00')
    await expect(cash.time).toHaveValue('2026-11-02T10:00')
    await expect(cash.remark).toHaveValue('断网')

    await page.reload()
    await expect(page.getByTestId('cash-summary-error')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('cash-ledger-error')).toBeVisible({ timeout: 20_000 })

    await backend.start()
    await page.getByTestId('cash-summary-retry').click()
    await page.getByTestId('cash-ledger-retry').click()
    await cash.expectSummary('0.00', '0.00', '0.00')
    await expect(cash.ledgerEmpty).toBeVisible()
  })
})
