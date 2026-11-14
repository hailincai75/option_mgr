import { Button, Card, Checkbox, Col, Form, Input, Row, Space } from 'antd'
import { STOCK_RECORD_TYPES, stockTypeLabel } from './stockLabels'
import type { StockFilterField, StockFilterValues } from './stockListFilters'

const TYPE_OPTION_LABELS: Record<string, string> = {
  BUY: '买入',
  SELL: '卖出',
  TRANSFER_IN: '转入（期初持仓）',
  ASSIGN_BUY: '指派买入',
  ASSIGN_SELL: '指派卖出',
  EXERCISE_BUY: '行权买入',
  EXERCISE_SELL: '行权卖出',
}

interface StockFilterFormProps {
  /** Applied filters (from the URL). The parent remounts this form when they change. */
  initialValues: StockFilterValues
  /** Errors of the applied filters (client checks or backend field errors). */
  errors: Partial<Record<StockFilterField, string>>
  onApply: (filters: StockFilterValues) => void
  onClear: () => void
}

/** 筛选 of the 股票 page: nothing is queried while typing; "查询" applies, "清除筛选" resets. */
export function StockFilterForm({ initialValues, errors, onApply, onClear }: StockFilterFormProps) {
  const [form] = Form.useForm<StockFilterValues>()
  const itemProps = (field: StockFilterField) => {
    const error = errors[field]
    return error === undefined
      ? { name: field }
      : { name: field, validateStatus: 'error' as const, help: error }
  }

  return (
    <Card size="small" title="筛选" data-testid="stock-filter-card">
      <Form<StockFilterValues>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={(values) => {
          onApply({
            symbol: values.symbol,
            type: values.type,
            startDate: values.startDate,
            endDate: values.endDate,
          })
        }}
        data-testid="stock-filter-form"
      >
        <Row gutter={16}>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="标的" {...itemProps('symbol')}>
              <Input placeholder="例如 IXQZ" autoComplete="off" data-testid="stock-filter-symbol" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="类型（可多选）" {...itemProps('type')}>
              <Checkbox.Group data-testid="stock-filter-type">
                {STOCK_RECORD_TYPES.map((type) => (
                  <Checkbox
                    key={type}
                    value={type}
                    data-testid={`stock-filter-type-${type.toLowerCase().replace('_', '-')}`}
                  >
                    {TYPE_OPTION_LABELS[type] ?? stockTypeLabel(type)}
                  </Checkbox>
                ))}
              </Checkbox.Group>
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="开始日期（美东日期）" {...itemProps('startDate')}>
              <Input type="date" data-testid="stock-filter-start-date" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Form.Item label="结束日期（美东日期）" {...itemProps('endDate')}>
              <Input type="date" data-testid="stock-filter-end-date" />
            </Form.Item>
          </Col>
        </Row>
        <Space>
          <Button type="primary" htmlType="submit" data-testid="stock-filter-submit">
            查询
          </Button>
          <Button onClick={onClear} data-testid="stock-filter-clear">
            清除筛选
          </Button>
        </Space>
      </Form>
    </Card>
  )
}
