import { Alert, Button, Space } from 'antd'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAccount } from '../../account/AccountContext'
import { switchToAdjustText } from '../../account/allAccountsLabels'
import { useAccountQuery } from '../../account/useAccountQuery'
import { adhocClosureAlertQueryKeys, getAdhocClosureAlerts } from '../../api/adhocClosureAlerts'
import type { AdhocClosureAccountCount, AdhocClosureAlert } from '../../api/types'
import type { AdhocClosureLocateState } from '../position/adhocClosureLocate'

const ADJUST_LINK_TEXT = '去调整'

/**
 * FEAT-042 rule 4: one red line per active ad-hoc closure date with affected positions, in backend
 * order, `message` shown verbatim plus 去调整. No close button (Q128); nothing at all when the list
 * is empty (Q129). Nothing is shown while loading or when the request fails — like the FEAT-009
 * stale warning, the pages keep working without it.
 *
 * `onAdjust` given (positions page): 去调整 calls it (scroll to the first affected row); otherwise
 * (home page) 去调整 links to the positions page.
 *
 * FEAT-035 (Q221) 全部账户: instead of 去调整, one `切换到 <账户> 去调整` per entry of the backend's
 * `byAccount`; a click switches to that account and opens the positions page (URL filters kept on
 * the positions page), which then scrolls to that account's first affected row.
 */
export function AdhocClosureAlerts({
  onAdjust,
}: {
  onAdjust?: (alert: AdhocClosureAlert) => void
}) {
  const { isAllAccounts, selectAccount } = useAccount()
  const navigate = useNavigate()
  const { pathname, search } = useLocation()
  const { data } = useAccountQuery({
    // FEAT-033: per-account key, so an account switch fetches that account's alerts.
    queryKey: adhocClosureAlertQueryKeys.all,
    queryFn: ({ signal }) => getAdhocClosureAlerts(signal),
  })
  const alerts = data?.alerts ?? []
  if (alerts.length === 0) return null

  const switchAndAdjust = (alert: AdhocClosureAlert, account: AdhocClosureAccountCount) => {
    const state: AdhocClosureLocateState = {
      adhocClosureDate: alert.date,
      adhocClosureAccountId: account.accountId,
    }
    selectAccount(account.accountId)
    void navigate({ pathname: '/positions', search: pathname === '/positions' ? search : '' }, { state })
  }

  const action = (alert: AdhocClosureAlert) => {
    if (isAllAccounts) {
      return (
        <Space size={4} wrap>
          {(alert.byAccount ?? []).map((account) => (
            <Button
              key={account.accountId}
              type="link"
              size="small"
              data-testid={`adhoc-closure-alert-switch-${String(account.accountId)}`}
              onClick={() => {
                switchAndAdjust(alert, account)
              }}
            >
              {switchToAdjustText(account.accountName)}
            </Button>
          ))}
        </Space>
      )
    }
    return onAdjust ? (
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
          action={action(alert)}
        />
      ))}
    </Space>
  )
}
