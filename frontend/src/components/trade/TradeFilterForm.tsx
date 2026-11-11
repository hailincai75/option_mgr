import { Button, Card, Checkbox, Col, Form, Input, Radio, Row, Space } from 'antd'
import type { TradeFilterField, TradeFilterValues } from './tradeListFilters'
import { TRADE_ACTIONS } from './tradeListFilters'

const ACTION_OPTION_LABELS: Record<string, string> = {
  BTO: 'BTO 买入开仓',
  STO: 'STO 卖出开仓',
  BTC: 'BTC 买入平仓',
  STC: 'STC 卖出平仓',
}

interface TradeFilterFormProps {
  /** Applied filters (from the URL). The parent remounts this form when they change. */
  initialValues: TradeFilterValues
  /** Errors of the applied filters (client checks or backend field errors). */
  errors: Partial<Record<TradeFilterField, string>>
  onApply: (filters: TradeFilterValues) => void
  onClear: () => void
}

/**
 * 筛选区 of the trade list. Nothing is queried while typing; "查询" applies the values
 * (the page validates them and puts them in the URL), "清除筛选" resets everything.
 */
export function TradeFilterForm({ initialValues, errors, onApply, onClear }: TradeFilterFormProps) {
  const [form] = Form.useForm<TradeFilterValues>()

  const itemProps = (field: TradeFilterField) => {
    const error = errors[field]
    return error === undefined
      ? { name: field }
      : { name: field, validateStatus: 'error' as const, help: error }
  }

  return (
    <Card size="small" title="筛选" data-testid="trade-filter-card">
      <Form<TradeFilterValues>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={(values) => {
          onApply({
            symbol: values.symbol,
            expirationDate: values.expirationDate,
            strike: values.strike,
            optionType: values.optionType,
            action: values.action,
            startDate: values.startDate,
            endDate: values.endDate,
          })
        }}
        data-testid="trade-filter-form"
      >
        <Row gutter={16}>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="标的" {...itemProps('symbol')}>
              <Input placeholder="例如 AAPL" autoComplete="off" data-testid="trade-filter-symbol" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="到期日" {...itemProps('expirationDate')}>
              <Input type="date" data-testid="trade-filter-expiration" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="行权价" {...itemProps('strike')}>
              <Input
                inputMode="decimal"
                placeholder="例如 200"
                autoComplete="off"
                data-testid="trade-filter-strike"
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="类型" {...itemProps('optionType')}>
              <Radio.Group data-testid="trade-filter-type">
                <Radio value="" data-testid="trade-filter-type-any">
                  不限
                </Radio>
                <Radio value="CALL" data-testid="trade-filter-type-call">
                  Call
                </Radio>
                <Radio value="PUT" data-testid="trade-filter-type-put">
                  Put
                </Radio>
              </Radio.Group>
            </Form.Item>
          </Col>
          <Col xs={24} lg={12}>
            <Form.Item label="方向（可多选）" {...itemProps('action')}>
              <Checkbox.Group data-testid="trade-filter-action">
                {TRADE_ACTIONS.map((action) => (
                  <Checkbox
                    key={action}
                    value={action}
                    data-testid={`trade-filter-action-${action.toLowerCase()}`}
                  >
                    {ACTION_OPTION_LABELS[action]}
                  </Checkbox>
                ))}
              </Checkbox.Group>
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="开始日期（美东日期）" {...itemProps('startDate')}>
              <Input type="date" data-testid="trade-filter-start-date" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="结束日期（美东日期）" {...itemProps('endDate')}>
              <Input type="date" data-testid="trade-filter-end-date" />
            </Form.Item>
          </Col>
        </Row>
        <Space>
          <Button type="primary" htmlType="submit" data-testid="trade-filter-submit">
            查询
          </Button>
          <Button onClick={onClear} data-testid="trade-filter-clear">
            清除筛选
          </Button>
        </Space>
      </Form>
    </Card>
  )
}
