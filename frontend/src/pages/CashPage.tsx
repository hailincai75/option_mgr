import { Col, Row, Space } from 'antd'
import { useState } from 'react'
import { CashLedgerTable } from '../components/cash/CashLedgerTable'
import { CashSummaryCard } from '../components/cash/CashSummaryCard'
import { CashTransferForm } from '../components/cash/CashTransferForm'

/** 资金 page (FEAT-007): cash summary, deposit/withdraw form and the cash ledger. */
export function CashPage() {
  const [ledgerPage, setLedgerPage] = useState(1)

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }} data-testid="cash-page">
      <CashSummaryCard />
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={9}>
          <CashTransferForm
            onSaved={() => {
              setLedgerPage(1)
            }}
          />
        </Col>
        <Col xs={24} xl={15}>
          <CashLedgerTable page={ledgerPage} onPageChange={setLedgerPage} />
        </Col>
      </Row>
    </Space>
  )
}
