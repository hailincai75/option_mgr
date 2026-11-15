import { Button, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import { useAccountQuery } from '../../account/useAccountQuery'
import { ApiError } from '../../api/client'
import { getGreeksSummary, greeksSummaryQueryKeys } from '../../api/greeksSummary'
import type { GreeksSummaryGroup } from '../../api/types'
import { HelpIcon } from './GreeksCells'
import {
  exposureText,
  GREEKS_SUMMARY_HINTS,
  GREEKS_SUMMARY_NOTE,
  GREEKS_SUMMARY_TITLE,
  groupNotes,
  PORTFOLIO_LABEL,
  portfolioNotes,
  signedValue,
  underlyingPriceText,
} from './greeksSummaryLabels'

const NUMERIC = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const

function HelpHeader({ id, title, hint }: { id: string; title: string; hint: string }) {
  return (
    <span data-testid={`greeks-summary-header-${id}`} style={{ whiteSpace: 'nowrap' }}>
      {title}
      <HelpIcon label={title} hint={hint} testId={`greeks-summary-header-${id}-help`} />
    </span>
  )
}

function Value({ testId, text, muted = false }: { testId: string; text: string; muted?: boolean }) {
  return muted ? (
    <Typography.Text type="secondary" data-testid={testId} style={NUMERIC}>
      {text}
    </Typography.Text>
  ) : (
    <span data-testid={testId} style={NUMERIC}>
      {text}
    </span>
  )
}

/** Signed Greek / Delta value; `—` (gray) when the backend gives null. */
function signedCell(testId: string, value: string | null): ReactNode {
  return <Value testId={testId} text={signedValue(value)} muted={value === null} />
}

const COLUMNS: ColumnsType<GreeksSummaryGroup> = [
  {
    key: 'symbol',
    title: '标的',
    render: (_, group) => (
      <div style={{ minWidth: 160 }}>
        <span data-testid="greeks-summary-symbol">{group.symbol}</span>
        {groupNotes(group).map((note) => (
          <Typography.Text
            key={note.key}
            type="secondary"
            data-testid={`greeks-summary-${note.key}-note`}
            style={{ display: 'block', fontSize: 12 }}
          >
            {note.text}
          </Typography.Text>
        ))}
      </div>
    ),
  },
  {
    key: 'optionDelta',
    title: '期权 Delta',
    align: 'right',
    render: (_, group) => signedCell('greeks-summary-option-delta', group.optionDelta),
  },
  {
    key: 'stockDelta',
    title: <HelpHeader id="stock-delta" title="股票 Delta" hint={GREEKS_SUMMARY_HINTS.stockDelta} />,
    align: 'right',
    render: (_, group) => signedCell('greeks-summary-stock-delta', group.stockDelta),
  },
  {
    key: 'netDelta',
    title: <HelpHeader id="net-delta" title="净 Delta" hint={GREEKS_SUMMARY_HINTS.netDelta} />,
    align: 'right',
    render: (_, group) => signedCell('greeks-summary-net-delta', group.netDelta),
  },
  {
    key: 'underlyingPrice',
    title: '标的价',
    align: 'right',
    render: (_, group) => {
      const price = underlyingPriceText(group)
      return (
        <span data-manual={group.underlyingPriceManual ? 'true' : 'false'}>
          <Value testId="greeks-summary-underlying-price" text={price.text} muted={price.muted} />
        </span>
      )
    },
  },
  {
    key: 'exposure',
    title: <HelpHeader id="exposure" title="Delta 美元敞口" hint={GREEKS_SUMMARY_HINTS.exposure} />,
    align: 'right',
    render: (_, group) => {
      const exposure = exposureText(group)
      return (
        <span data-status={group.exposureStatus}>
          <Value testId="greeks-summary-exposure" text={exposure.text} muted={exposure.muted} />
        </span>
      )
    },
  },
  {
    key: 'gamma',
    title: 'Gamma',
    align: 'right',
    render: (_, group) => signedCell('greeks-summary-gamma', group.gamma),
  },
  {
    key: 'theta',
    title: 'Theta',
    align: 'right',
    render: (_, group) => signedCell('greeks-summary-theta', group.theta),
  },
  {
    key: 'vega',
    title: 'Vega',
    align: 'right',
    render: (_, group) => signedCell('greeks-summary-vega', group.vega),
  },
]

/**
 * FEAT-019 按标的 Greeks 汇总 (shown only while 显示 Greeks is on, Q175): one row per symbol and
 * the 全组合 row from `GET /api/positions/greeks-summary` for the current account (never filtered
 * by 到期, Q174). Formatting only — every number and sentence is the backend's. Reloads whenever
 * the positions do (same invalidations as FEAT-017's totals).
 */
export function GreeksSummaryCard() {
  const { data, error, isPending, isFetching, refetch } = useAccountQuery({
    queryKey: greeksSummaryQueryKeys.all,
    queryFn: ({ signal }) => getGreeksSummary(signal),
  })

  let body
  if (isPending) {
    body = (
      <Typography.Text type="secondary" data-testid="greeks-summary-loading">
        Greeks 汇总加载中…
      </Typography.Text>
    )
  } else if (error) {
    const message = error instanceof ApiError ? `${error.message} (${error.code})` : error.message
    body = (
      <Space size={8} wrap>
        <Typography.Text type="danger" data-testid="greeks-summary-error">
          无法加载 Greeks 汇总：{message}
        </Typography.Text>
        <Button
          size="small"
          loading={isFetching}
          onClick={() => void refetch()}
          data-testid="greeks-summary-retry"
        >
          重新加载汇总
        </Button>
      </Space>
    )
  } else {
    const { portfolio } = data
    const notes = portfolioNotes(portfolio)
    body = (
      <Table<GreeksSummaryGroup>
        size="small"
        rowKey="symbol"
        columns={COLUMNS}
        dataSource={data.groups}
        loading={isFetching}
        pagination={false}
        locale={{ emptyText: <span data-testid="greeks-summary-empty">暂无持仓</span> }}
        onRow={(group) =>
          ({
            'data-testid': 'greeks-summary-row',
            'data-symbol': group.symbol,
          }) as HTMLAttributes<HTMLElement>
        }
        summary={() => (
          <Table.Summary>
            <Table.Summary.Row data-testid="greeks-summary-portfolio">
              <Table.Summary.Cell index={0}>
                <Typography.Text strong>{PORTFOLIO_LABEL}</Typography.Text>
              </Table.Summary.Cell>
              <Table.Summary.Cell index={1} />
              <Table.Summary.Cell index={2} />
              <Table.Summary.Cell index={3} />
              <Table.Summary.Cell index={4} />
              <Table.Summary.Cell index={5} align="right">
                <Value
                  testId="greeks-summary-portfolio-exposure"
                  text={signedValue(portfolio.dollarExposure)}
                />
              </Table.Summary.Cell>
              <Table.Summary.Cell index={6} />
              <Table.Summary.Cell index={7} align="right">
                <Value testId="greeks-summary-portfolio-theta" text={signedValue(portfolio.theta)} />
              </Table.Summary.Cell>
              <Table.Summary.Cell index={8} align="right">
                <Value testId="greeks-summary-portfolio-vega" text={signedValue(portfolio.vega)} />
              </Table.Summary.Cell>
            </Table.Summary.Row>
            {notes.length > 0 && (
              <Table.Summary.Row>
                <Table.Summary.Cell index={0} colSpan={COLUMNS.length}>
                  {notes.map((note) => (
                    <Typography.Text
                      key={note.key}
                      type="secondary"
                      data-testid={`greeks-summary-portfolio-${note.key}`}
                      style={{ display: 'block', fontSize: 12 }}
                    >
                      {note.text}
                    </Typography.Text>
                  ))}
                </Table.Summary.Cell>
              </Table.Summary.Row>
            )}
          </Table.Summary>
        )}
      />
    )
  }

  return (
    <div data-testid="greeks-summary">
      <Typography.Text strong data-testid="greeks-summary-title">
        {GREEKS_SUMMARY_TITLE}
        <HelpIcon
          label={GREEKS_SUMMARY_TITLE}
          hint={GREEKS_SUMMARY_HINTS.title}
          testId="greeks-summary-title-help"
        />
      </Typography.Text>
      <div>
        <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="greeks-summary-note">
          {GREEKS_SUMMARY_NOTE}
        </Typography.Text>
      </div>
      <div style={{ marginTop: 8 }} data-testid="greeks-summary-body">
        {body}
      </div>
    </div>
  )
}
