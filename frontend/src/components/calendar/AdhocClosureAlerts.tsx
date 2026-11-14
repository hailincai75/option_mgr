import { Alert, Button, Space } from 'antd'
import { Link } from 'react-router-dom'
import { useAccountQuery } from '../../account/useAccountQuery'
import { adhocClosureAlertQueryKeys, getAdhocClosureAlerts } from '../../api/adhocClosureAlerts'
import type { AdhocClosureAlert } from '../../api/types'

const ADJUST_LINK_TEXT = '去调整'

/**
 * FEAT-042 rule 4: one red line per active ad-hoc closure date with affected positions, in backend
 * order, `message` shown verbatim plus 去调整. No close button (Q128); nothing at all when the list
 * is empty (Q129). Nothing is shown while loading or when the request fails — like the FEAT-009
 * stale warning, the pages keep working without it.
 *
 * `onAdjust` given (positions page): 去调整 calls it (scroll to the first affected row); otherwise
 * (home page) 去调整 links to the positions page.
 */
export function AdhocClosureAlerts({
  onAdjust,
}: {
  onAdjust?: (alert: AdhocClosureAlert) => void
}) {
  const { data } = useAccountQuery({
    // FEAT-033: per-account key, so an account switch fetches that account's alerts.
    queryKey: adhocClosureAlertQueryKeys.all,
    queryFn: ({ signal }) => getAdhocClosureAlerts(signal),
  })
  const alerts = data?.alerts ?? []
  if (alerts.length === 0) return null
  return (
    <Space orientation="vertical" size={8} style={{ width: '100%' }} data-testid="adhoc-closure-alerts">
      {alerts.map((alert) => (
        <Alert
          key={alert.date}
          type="error"
          showIcon
          data-testid="adhoc-closure-alert"
          data-date={alert.date}
          title={<span data-testid="adhoc-closure-alert-message">{alert.message}</span>}
          action={
            onAdjust ? (
              <Button
                type="link"
                size="small"
                data-testid="adhoc-closure-alert-adjust"
                onClick={() => {
                  onAdjust(alert)
                }}
              >
                {ADJUST_LINK_TEXT}
              </Button>
            ) : (
              <Link to="/positions" data-testid="adhoc-closure-alert-adjust">
                {ADJUST_LINK_TEXT}
              </Link>
            )
          }
        />
      ))}
    </Space>
  )
}
