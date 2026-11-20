import { expect, type Locator } from '@playwright/test'

/**
 * FEAT-051 lot picker (components/trade/CloseLotPicker; testids in docs/architecture.md "FEAT-051 testid").
 * Prefix `close-trade` = positions-page close dialog, `trade-edit` = edit form of a BTC / STC.
 * Rows: `<p>-lot-row[data-trade-id][data-lot-index][data-selected]`, checkbox `<p>-lot-checkbox`,
 * contracts cell `<p>-lot-contracts` ("N 张"), total `<p>-lots-total` ("平仓张数：N").
 */
export type LotPrefix = 'close-trade' | 'trade-edit'

export function lotRows(scope: Locator, prefix: LotPrefix = 'close-trade'): Locator {
  return scope.getByTestId(`${prefix}-lot-row`)
}

/** Ticks exactly the lots whose 1-based list positions are in `indices` (all others unticked). */
export async function chooseLotsByIndex(scope: Locator, indices: number[], prefix: LotPrefix = 'close-trade'): Promise<void> {
  const rows = lotRows(scope, prefix)
  await expect(rows.first()).toBeVisible()
  const n = await rows.count()
  for (let i = 0; i < n; i++) {
    const row = rows.nth(i)
    const index = Number(await row.getAttribute('data-lot-index'))
    await row.getByTestId(`${prefix}-lot-checkbox`).setChecked(indices.includes(index))
  }
  for (let i = 0; i < n; i++) {
    const row = rows.nth(i)
    const index = Number(await row.getAttribute('data-lot-index'))
    await expect(row).toHaveAttribute('data-selected', indices.includes(index) ? 'true' : 'false')
  }
}

/** Ticks exactly the lots with these opening trade ids. */
export async function chooseLotsById(scope: Locator, tradeIds: number[], prefix: LotPrefix = 'close-trade'): Promise<void> {
  const rows = lotRows(scope, prefix)
  await expect(rows.first()).toBeVisible()
  const n = await rows.count()
  for (let i = 0; i < n; i++) {
    const row = rows.nth(i)
    const id = Number(await row.getAttribute('data-trade-id'))
    await row.getByTestId(`${prefix}-lot-checkbox`).setChecked(tradeIds.includes(id))
    await expect(row).toHaveAttribute('data-selected', tradeIds.includes(id) ? 'true' : 'false')
  }
}

/**
 * Replacement for the removed `close-trade-contracts-<N>` radio: ticks the earliest whole lots (list order = opening
 * time) whose contracts add up to exactly `contracts` — the lots the old FIFO count selected — and checks the total.
 */
export async function chooseFifoLots(scope: Locator, contracts: number, prefix: LotPrefix = 'close-trade'): Promise<void> {
  const rows = lotRows(scope, prefix)
  await expect(rows.first()).toBeVisible()
  const sizes = await rows.evaluateAll(
    (rs, p) => rs.map((r) => Number((r.querySelector(`[data-testid="${p}-lot-contracts"]`)?.textContent ?? '').replace(/[^0-9]/g, ''))),
    prefix,
  )
  const pick: number[] = []
  let sum = 0
  for (let i = 0; i < sizes.length && sum < contracts; i++) {
    sum += sizes[i]!
    pick.push(i + 1)
  }
  if (sum !== contracts) throw new Error(`no earliest whole lots add up to ${contracts}: lots ${JSON.stringify(sizes)}`)
  await chooseLotsByIndex(scope, pick, prefix)
  await expect(scope.getByTestId(`${prefix}-lots-total`)).toHaveText(`平仓张数：${contracts}`)
}

/** "time action contracts net [FIFO 默认]" of every lot row, top to bottom. */
export async function lotLines(scope: Locator, prefix: LotPrefix = 'close-trade'): Promise<string[]> {
  return lotRows(scope, prefix).evaluateAll(
    (rs, p) =>
      rs.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${p}-${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
        return [t('lot-time'), t('lot-action'), t('lot-contracts'), t('lot-net-amount'), t('lot-fifo-default')]
          .filter((x) => x !== '')
          .join(' ')
      }),
    prefix,
  )
}

/** data-selected of every lot row, top to bottom. */
export async function lotSelection(scope: Locator, prefix: LotPrefix = 'close-trade'): Promise<boolean[]> {
  return lotRows(scope, prefix).evaluateAll((rs) => rs.map((r) => r.getAttribute('data-selected') === 'true'))
}
