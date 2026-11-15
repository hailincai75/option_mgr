import { Col, Row, Space } from 'antd'
import { useState } from 'react'
import { useIsAllAccounts } from '../account/AccountContext'
import { PAGE_NOT_SUPPORTED_TEXT } from '../account/allAccountsLabels'
import { AllAccountsPlaceholder } from '../components/account/AllAccountsPlaceholder'
import { CashLedgerTable } from '../components/cash/CashLedgerTable'
import { CashSummaryCard } from '../components/cash/CashSummaryCard'
import { CashTransferForm } from '../components/cash/CashTransferForm'

/**
 * 资金 page (FEAT-007): cash summary, deposit/withdraw form and the cash ledger.
 * FEAT-035 全部账户: one summary row per account plus 合计; no deposit / withdraw form (rule 5);
 * the ledger is a placeholder until FEAT-046 (rule 7).
 */
export function CashPage() {
  const [ledgerPage, setLedgerPage] = useState(1)
  const allAccounts = useIsAllAccounts()

  if (allAccounts) {
    return (
      <Space orientation="vertical" size={16} style={{ width: '100%' }} data-testid="cash-page">
        <CashSummaryCard perAccount />
        <AllAccountsPlaceholder
          title="现金流水"
          message={PAGE_NOT_SUPPORTED_TEXT}
          testId="cash-ledger-all-accounts"
        />
      </Space>
    )
  }

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
