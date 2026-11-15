import { Tooltip, Typography } from 'antd'
import type { OptionGreeks } from '../../api/types'
import { EMPTY_VALUE } from '../../utils/format'
import { greeksDisplay, type GreekKey, type GreeksColumnKey } from './greeksLabels'

const NUMERIC = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const

/**
 * Circled `?` whose hover shows a plain-language hint (Q169). Shared by the FEAT-018 Greeks
 * columns and the FEAT-019 summary headers.
 */
export function HelpIcon({ label, hint, testId }: { label: string; hint: string; testId: string }) {
  return (
    <Tooltip title={hint}>
      <span
        role="img"
        aria-label={`${label} 说明`}
        data-testid={testId}
        style={{
          display: 'inline-block',
          marginLeft: 4,
          width: 14,
          height: 14,
          lineHeight: '12px',
          fontSize: 10,
          textAlign: 'center',
          borderRadius: '50%',
          border: '1px solid currentColor',
          opacity: 0.55,
          cursor: 'help',
          fontWeight: 'normal',
        }}
      >
        ?
      </span>
    </Tooltip>
  )
}

/**
 * FEAT-018 column header: the title and a `?` whose hover shows the plain-language hint (Q169).
 */
export function GreeksHeader({
  columnKey,
  title,
  hint,
}: {
  columnKey: GreeksColumnKey
  title: string
  hint: string
}) {
  return (
    <span data-testid={`greeks-header-${columnKey}`} style={{ whiteSpace: 'nowrap' }}>
      {title}
      <HelpIcon label={title} hint={hint} testId={`greeks-header-${columnKey}-help`} />
    </span>
  )
}

/**
 * FEAT-018 IV cell: the backend's `ivPercent` + `%` (`—` when null), or the backend's `label` in
 * gray; below it the backend's manual-mark note in small gray text when present (Q170).
 */
export function GreeksIvCell({ greeks }: { greeks: OptionGreeks | undefined }) {
  const display = greeksDisplay(greeks)
  const status = greeks?.status ?? ''
  if (display.kind !== 'value') {
    return (
      <Typography.Text
        type="secondary"
        data-testid="position-greeks-iv"
        data-status={status}
        style={NUMERIC}
      >
        {display.kind === 'label' ? display.label : EMPTY_VALUE}
      </Typography.Text>
    )
  }
  return (
    <div style={{ textAlign: 'right', lineHeight: 1.3 }}>
      <div data-testid="position-greeks-iv" data-status={status} style={NUMERIC}>
        {display.iv}
      </div>
      {display.manualMarkNote !== null && (
        <Typography.Text
          type="secondary"
          data-testid="position-greeks-manual-note"
          style={{ display: 'block', fontSize: 12, minWidth: 160, maxWidth: 200, textAlign: 'left' }}
        >
          {display.manualMarkNote}
        </Typography.Text>
      )}
    </div>
  )
}

/**
 * FEAT-018 Delta / Gamma / Theta / Vega cell: position value with an explicit sign on top, the
 * per-contract value in small gray text below (each `—` when null, Q172), or the backend's `label`
 * in gray. Not colored: Greeks are sensitivities, not P&L.
 */
export function GreekValueCell({
  greeks,
  greek,
}: {
  greeks: OptionGreeks | undefined
  greek: GreekKey
}) {
  const display = greeksDisplay(greeks)
  const testId = `position-greeks-${greek}`
  const status = greeks?.status ?? ''
  if (display.kind !== 'value') {
    return (
      <Typography.Text type="secondary" data-testid={testId} data-status={status} style={NUMERIC}>
        {display.kind === 'label' ? display.label : EMPTY_VALUE}
      </Typography.Text>
    )
  }
  const value = display.greeks[greek]
  return (
    <div
      data-testid={testId}
      data-status={status}
      style={{ ...NUMERIC, textAlign: 'right', lineHeight: 1.3 }}
    >
      <div data-testid={`${testId}-position`}>{value.position}</div>
      <Typography.Text
        type="secondary"
        data-testid={`${testId}-per-contract`}
        style={{ display: 'block', fontSize: 12 }}
      >
        {value.perContract}
      </Typography.Text>
    </div>
  )
}
