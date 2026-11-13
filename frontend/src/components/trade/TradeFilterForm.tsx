import { Alert, Button, Card, Checkbox, Col, Form, Input, Radio, Row, Space } from 'antd'
import type { TradeAction } from '../../api/types'
import type { TradeFilterField, TradeFilterValues } from './tradeListFilters'
import { TRADE_ACTIONS } from './tradeListFilters'

const ACTION_OPTION_LABELS: Record<string, string> = {
  BTO: 'BTO 买入开仓',
  STO: 'STO 卖出开仓',
  BTC: 'BTC 买入平仓',
  STC: 'STC 卖出平仓',
  EXP: '到期作废',
}

/** Form values; `type` (record type, FEAT-040) only when `recordType` is set. */
export interface TradeFilterFormValues extends TradeFilterValues {
  type?: string
}

export type TradeFilterFormField = TradeFilterField | 'type'

/**
 * FEAT-040 (Q90) record type filter of the 已平仓 page: 不限 / 期权 / 股票. With 股票 the
 * option-only filters are disabled (and emptied when switching); with 不限 and any of them set
 * the hint is shown.
 */
export interface RecordTypeFilterOptions {
  /** Shown when 类型 = 不限 and an option-only filter has a value. */
  optionOnlyHint: string
}

interface TradeFilterFormProps {
  /** Applied filters (from the URL). The parent remounts this form when they change. */
  initialValues: TradeFilterFormValues
  /** Errors of the applied filters (client checks or backend field errors). */
  errors: Partial<Record<TradeFilterFormField, string>>
  onApply: (filters: TradeFilterFormValues) => void
  onClear: () => void
  /** Direction options (default all five; FEAT-006 uses BTC / STC / EXP). */
  actions?: readonly TradeAction[]
  /** Prefix for the date labels, e.g. "平仓" → "平仓开始日期（美东日期）". */
  dateLabelPrefix?: string
  /** Label of the CALL / PUT field (default "类型"). */
  optionTypeLabel?: string
  /** FEAT-040: show the record type filter (已平仓 page). */
  recordType?: RecordTypeFilterOptions
  /** data-testid prefix (default `trade-filter`). */
  testIdPrefix?: string
}

const OPTION_ONLY_EMPTY = { expirationDate: '', strike: '', optionType: '', action: [] }

function hasOptionOnlyValue(values: Partial<TradeFilterFormValues>): boolean {
  return (
    (values.expirationDate ?? '').trim() !== '' ||
    (values.strike ?? '').trim() !== '' ||
    (values.optionType ?? '').trim() !== '' ||
    (values.action ?? []).length > 0
  )
}

/**
 * 筛选区 of the trade list. Nothing is queried while typing; "查询" applies the values
 * (the page validates them and puts them in the URL), "清除筛选" resets everything.
 */
export function TradeFilterForm({
  initialValues,
  errors,
  onApply,
  onClear,
  actions = TRADE_ACTIONS,
  dateLabelPrefix = '',
  optionTypeLabel = '类型',
  recordType,
  testIdPrefix = 'trade-filter',
}: TradeFilterFormProps) {
  const tid = (suffix: string) => `${testIdPrefix}-${suffix}`
  const [form] = Form.useForm<TradeFilterFormValues>()
  // Current (unsubmitted) values; undefined until the form is registered.
  const watched = Form.useWatch((values: TradeFilterFormValues) => values, form) as
    | Partial<TradeFilterFormValues>
    | undefined
  const current: Partial<TradeFilterFormValues> = watched ?? initialValues
  const currentType = (current.type ?? '').trim()
  const stockOnly = recordType !== undefined && currentType === 'STOCK'
  const showOptionOnlyHint =
    recordType !== undefined && currentType === '' && hasOptionOnlyValue(current)

  const itemProps = (field: TradeFilterFormField) => {
    const error = errors[field]
    return error === undefined
      ? { name: field }
      : { name: field, validateStatus: 'error' as const, help: error }
  }

  return (
    <Card size="small" title="筛选" data-testid={tid('card')}>
      <Form<TradeFilterFormValues>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={(values) => {
          const filters: TradeFilterFormValues = {
            symbol: values.symbol,
            expirationDate: values.expirationDate,
            strike: values.strike,
            optionType: values.optionType,
            action: values.action,
            startDate: values.startDate,
            endDate: values.endDate,
          }
          if (recordType !== undefined) filters.type = values.type ?? ''
          onApply(filters)
        }}
        data-testid={tid('form')}
      >
        <Row gutter={16}>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="标的" {...itemProps('symbol')}>
              <Input placeholder="例如 AAPL" autoComplete="off" data-testid={tid('symbol')} />
            </Form.Item>
          </Col>
          {recordType !== undefined && (
            <Col xs={24} sm={12} lg={6}>
              <Form.Item label="类型" {...itemProps('type')}>
                <Radio.Group
                  data-testid={tid('record-type')}
                  onChange={(event) => {
                    const next = String(event.target.value)
                    // Q90: switching to / from 股票 empties the option-only filters (values
                    // from the URL are kept only until the type is changed).
                    if (next === 'STOCK' || currentType === 'STOCK') {
                      form.setFieldsValue(OPTION_ONLY_EMPTY)
                    }
                  }}
                >
                  <Radio value="" data-testid={tid('record-type-any')}>
                    不限
                  </Radio>
                  <Radio value="OPTION" data-testid={tid('record-type-option')}>
                    期权
                  </Radio>
                  <Radio value="STOCK" data-testid={tid('record-type-stock')}>
                    股票
                  </Radio>
                </Radio.Group>
              </Form.Item>
            </Col>
          )}
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="到期日" {...itemProps('expirationDate')}>
              <Input type="date" disabled={stockOnly} data-testid={tid('expiration')} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="行权价" {...itemProps('strike')}>
              <Input
                inputMode="decimal"
                placeholder="例如 200"
                autoComplete="off"
                disabled={stockOnly}
                data-testid={tid('strike')}
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label={optionTypeLabel} {...itemProps('optionType')}>
              <Radio.Group disabled={stockOnly} data-testid={tid('type')}>
                <Radio value="" data-testid={tid('type-any')}>
                  不限
                </Radio>
                <Radio value="CALL" data-testid={tid('type-call')}>
                  Call
                </Radio>
                <Radio value="PUT" data-testid={tid('type-put')}>
                  Put
                </Radio>
              </Radio.Group>
            </Form.Item>
          </Col>
          <Col xs={24} lg={12}>
            <Form.Item label="方向（可多选）" {...itemProps('action')}>
              <Checkbox.Group disabled={stockOnly} data-testid={tid('action')}>
                {actions.map((action) => (
                  <Checkbox
                    key={action}
                    value={action}
                    data-testid={tid(`action-${action.toLowerCase()}`)}
                  >
                    {ACTION_OPTION_LABELS[action]}
                  </Checkbox>
                ))}
              </Checkbox.Group>
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label={`${dateLabelPrefix}开始日期（美东日期）`} {...itemProps('startDate')}>
              <Input type="date" data-testid={tid('start-date')} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label={`${dateLabelPrefix}结束日期（美东日期）`} {...itemProps('endDate')}>
              <Input type="date" data-testid={tid('end-date')} />
            </Form.Item>
          </Col>
        </Row>
        {showOptionOnlyHint && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            title={<span data-testid={tid('option-only-hint')}>{recordType.optionOnlyHint}</span>}
          />
        )}
        <Space>
          <Button type="primary" htmlType="submit" data-testid={tid('submit')}>
            查询
          </Button>
          <Button onClick={onClear} data-testid={tid('clear')}>
            清除筛选
          </Button>
        </Space>
      </Form>
    </Card>
  )
}
