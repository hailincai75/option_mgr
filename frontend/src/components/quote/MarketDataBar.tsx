import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Tooltip, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { getMarketDataStatus, marketDataQueryKeys, refreshMarketData } from '../../api/marketData'
import { refreshQuotes } from './marketDataRefresh'
import {
  lastSuccessText,
  REFRESH_BUTTON_TEXT,
  REFRESH_COOLDOWN_HINT,
  refreshOutcome,
  refreshRecheckDelay,
  type RefreshOutcome,
} from './quoteLabels'

/** Contract suggestion: `stale` / `warning` are computed per request, so poll the status. */
const STATUS_POLL_MS = 60_000

type Outcome = RefreshOutcome | { kind: 'error'; text: string }

const OUTCOME_TEXT_TYPES = {
  success: 'success',
  partial: 'warning',
  cooldown: 'warning',
  error: 'danger',
} as const satisfies Record<Outcome['kind'], 'success' | 'warning' | 'danger'>

/**
 * FEAT-016 top of the positions page: the backend's `warning` verbatim while `stale` (Q141 /
 * Q146), and on the right 立即刷新 — disabled while refreshing and while the backend says
 * `refreshAllowed: false` (hover: 1 分钟内只能刷新一次). After a refresh the positions and status
 * reload; `performed: false` (COOLDOWN) shows a hint. A failing status request hides the warning
 * and leaves the button enabled (the backend still enforces the cooldown).
 */
export function MarketDataBar() {
  const queryClient = useQueryClient()
  const { data: status, refetch } = useQuery({
    queryKey: marketDataQueryKeys.status(),
    queryFn: ({ signal }) => getMarketDataStatus(signal),
    refetchInterval: STATUS_POLL_MS,
  })
  const [outcome, setOutcome] = useState<Outcome | null>(null)

  const mutation = useMutation({
    mutationFn: refreshMarketData,
    onMutate: () => {
      setOutcome(null)
    },
    onSuccess: async (response) => {
      queryClient.setQueryData(marketDataQueryKeys.status(), response.status)
      setOutcome(refreshOutcome(response))
      await refreshQuotes(queryClient)
    },
    onError: (error) => {
      setOutcome({ kind: 'error', text: `刷新失败：${error.message}` })
    },
  })

  // One extra status check when the cooldown should be over (the regular polling covers the rest).
  // Keyed on primitives so it is scheduled once per cooldown, not on every status poll.
  const availableAt = status?.refreshAvailableAt ?? null
  const refreshAllowed = status?.refreshAllowed ?? true
  useEffect(() => {
    const delay = refreshRecheckDelay(
      { refreshAllowed, refreshAvailableAt: availableAt },
      Date.now(),
    )
    if (delay === null) return
    const timer = window.setTimeout(() => {
      void refetch()
    }, delay)
    return () => {
      window.clearTimeout(timer)
    }
  }, [availableAt, refreshAllowed, refetch])

  const coolingDown = status?.refreshAllowed === false
  const warning = status?.stale === true ? status.warning : null
  const lastSuccess = lastSuccessText(status)

  return (
    <div
      data-testid="market-data-bar"
      style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}
    >
      <div style={{ flex: '1 1 320px', minWidth: 0 }}>
        {warning != null && warning !== '' && (
          <Alert
            type="warning"
            showIcon
            title={<span data-testid="market-data-stale-warning">{warning}</span>}
          />
        )}
      </div>
      <div
        style={{
          display: 'flex',
          gap: 12,
          alignItems: 'center',
          flexWrap: 'wrap',
          marginLeft: 'auto',
        }}
      >
        {outcome !== null && (
          <Typography.Text
            type={OUTCOME_TEXT_TYPES[outcome.kind]}
            data-testid="market-data-refresh-result"
            data-kind={outcome.kind}
          >
            {outcome.text}
          </Typography.Text>
        )}
        {lastSuccess !== null && (
          <Typography.Text type="secondary" data-testid="market-data-last-success">
            {lastSuccess}
          </Typography.Text>
        )}
        <Tooltip title={REFRESH_COOLDOWN_HINT}>
          {/* Wrapper so the hint also shows while the button is disabled. */}
          <span data-testid="market-data-refresh-wrapper">
            <Button
              onClick={() => {
                mutation.mutate()
              }}
              loading={mutation.isPending}
              disabled={mutation.isPending || coolingDown}
              data-testid="market-data-refresh"
            >
              {REFRESH_BUTTON_TEXT}
            </Button>
          </span>
        </Tooltip>
      </div>
    </div>
  )
}
