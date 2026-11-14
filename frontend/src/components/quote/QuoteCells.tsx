import { useMutation, useQueryClient } from '@tanstack/react-query'
import { App, Button, Space, Tag, Tooltip } from 'antd'
import { useState, type ReactNode } from 'react'
import { clearOptionOverride, clearUnderlyingOverride } from '../../api/marketData'
import type { OptionPosition, UnderlyingQuote } from '../../api/types'
import { refreshQuotes } from './marketDataRefresh'
import { ManualQuoteModal, type ManualQuoteTarget } from './ManualQuoteModal'
import {
  bidAskText,
  FINNHUB_SOURCE_HINT,
  isFinnhubPrice,
  MANUAL_CLEAR_TEXT,
  MANUAL_SET_TEXT,
  manualTagText,
  quotePriceText,
  quoteTimeText,
} from './quoteLabels'

const NUMERIC = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const

/** 清除: removes a manual override, then every position quote reloads. */
function ClearOverrideButton({ clear, testId }: { clear: () => Promise<void>; testId: string }) {
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const mutation = useMutation({
    mutationFn: clear,
    onSuccess: async () => {
      void message.success('已清除手动值')
      await refreshQuotes(queryClient)
    },
    onError: (error) => {
      void message.error(`清除失败：${error.message}`)
    },
  })
  return (
    <Button
      type="link"
      size="small"
      style={{ padding: 0, height: 'auto' }}
      loading={mutation.isPending}
      onClick={() => {
        mutation.mutate()
      }}
      data-testid={testId}
    >
      {MANUAL_CLEAR_TEXT}
    </Button>
  )
}

/** 手动设置 link that opens the dialog for `target`. */
function SetOverrideButton({ target, testId }: { target: ManualQuoteTarget; testId: string }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button
        type="link"
        size="small"
        style={{ padding: 0, height: 'auto' }}
        onClick={() => {
          setOpen(true)
        }}
        data-testid={testId}
      >
        {MANUAL_SET_TEXT}
      </Button>
      {open && (
        <ManualQuoteModal
          target={target}
          onDone={() => {
            setOpen(false)
          }}
        />
      )}
    </>
  )
}

function LabelTag({ text, color, testId }: { text: string; color: string; testId: string }) {
  return (
    <Tag color={color} style={{ marginInlineEnd: 0 }} data-testid={testId}>
      {text}
    </Tag>
  )
}

/** Value line + labels + actions, stacked in one table cell. */
function QuoteCellLayout({ children }: { children: ReactNode }) {
  return (
    <Space orientation="vertical" size={2} style={{ alignItems: 'flex-end' }}>
      {children}
    </Space>
  )
}

interface UnderlyingPriceCellProps {
  symbol: string
  /** Missing only in older fixtures: treated as 无报价. */
  quote: UnderlyingQuote | undefined
  /**
   * testid of the value; labels / actions append `-manual`, `-stale`, `-set`, `-clear`
   * (option rows: `position-underlying-price`, stock rows: `stock-position-price`).
   */
  testId: string
}

/**
 * 标的价 (option rows) / 现价 (stock rows): the backend's display price or 无报价; hover
 * `来源 Finnhub` for a Finnhub price; `手动 MM-DD HH:mm` + 清除 for an override; the backend's
 * `staleLabel` verbatim when the latest fetch failed; 手动设置 always.
 */
export function UnderlyingPriceCell({ symbol, quote, testId }: UnderlyingPriceCellProps) {
  const price = quote?.price ?? null
  const finnhub = isFinnhubPrice(quote)
  const value = (
    <span
      data-testid={testId}
      data-manual={quote?.manual === true ? 'true' : 'false'}
      data-source={quote?.source ?? ''}
      style={{ ...NUMERIC, cursor: finnhub ? 'help' : undefined }}
    >
      {quotePriceText(price)}
    </span>
  )
  return (
    <QuoteCellLayout>
      {finnhub ? <Tooltip title={FINNHUB_SOURCE_HINT}>{value}</Tooltip> : value}
      {quote?.manual === true && (
        <Space size={4}>
          <LabelTag
            text={manualTagText(quote.manualEnteredAtLocal)}
            color="purple"
            testId={`${testId}-manual`}
          />
          <ClearOverrideButton
            clear={() => clearUnderlyingOverride(symbol)}
            testId={`${testId}-clear`}
          />
        </Space>
      )}
      {quote?.staleLabel != null && (
        <LabelTag text={quote.staleLabel} color="warning" testId={`${testId}-stale`} />
      )}
      <SetOverrideButton
        target={{ kind: 'underlying', symbol, currentPrice: price }}
        testId={`${testId}-set`}
      />
    </QuoteCellLayout>
  )
}

/**
 * Mark of an option row: the backend's display mark or 无报价, `手动 MM-DD HH:mm` + 清除 for an
 * override, the backend's `contractStatusLabel` (数据源已无该合约…) and `staleLabel` verbatim,
 * 手动设置 always.
 */
export function OptionMarkCell({ position }: { position: OptionPosition }) {
  const quote = position.optionQuote
  const mark = quote?.mark ?? null
  const contract = {
    symbol: position.symbol,
    expirationDate: position.expirationDate,
    strike: position.strike,
    optionType: position.optionType,
  }
  return (
    <QuoteCellLayout>
      <span
        data-testid="position-mark"
        data-manual={quote?.manual === true ? 'true' : 'false'}
        data-contract-status={quote?.contractStatus ?? ''}
        style={NUMERIC}
      >
        {quotePriceText(mark)}
      </span>
      {quote?.manual === true && (
        <Space size={4}>
          <LabelTag
            text={manualTagText(quote.manualEnteredAtLocal)}
            color="purple"
            testId="position-mark-manual"
          />
          <ClearOverrideButton
            clear={() => clearOptionOverride(contract)}
            testId="position-mark-clear"
          />
        </Space>
      )}
      {quote?.contractStatusLabel != null && (
        <LabelTag text={quote.contractStatusLabel} color="default" testId="position-mark-status" />
      )}
      {quote?.staleLabel != null && (
        <LabelTag text={quote.staleLabel} color="warning" testId="position-mark-stale" />
      )}
      <SetOverrideButton
        target={{
          kind: 'option',
          contract,
          contractLabel: position.contractLabel,
          currentMark: mark,
        }}
        testId="position-mark-set"
      />
    </QuoteCellLayout>
  )
}

/** Bid / Ask of the last automatic quote (`—` when never quoted). */
export function BidAskCell({ position }: { position: OptionPosition }) {
  return (
    <span data-testid="position-bid-ask" style={NUMERIC}>
      {bidAskText(position.optionQuote)}
    </span>
  )
}

/** 报价时间: `MM-DD HH:mm` (New York) of the last automatic quote, `—` when none. */
export function QuoteTimeCell({
  quote,
  testId,
}: {
  quote: { quoteTimeLocal: string | null } | undefined
  testId: string
}) {
  return (
    <span data-testid={testId} style={NUMERIC}>
      {quoteTimeText(quote)}
    </span>
  )
}
