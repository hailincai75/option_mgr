import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, Form, Input, Modal, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState, type HTMLAttributes } from 'react'
import { useAccount } from '../account/AccountContext'
import { accountQueryKeys, createAccount, updateAccount } from '../api/accounts'
import { auditLogQueryKeys } from '../api/auditLog'
import type { Account } from '../api/types'
import { applySubmitError, toRule, type SubmitError } from '../components/trade/tradeFormSupport'
import { EMPTY_VALUE, formatDateTimeNY } from '../utils/format'

const ACCOUNT_NAME_MAX_LENGTH = 30
const BROKER_NOTE_MAX_LENGTH = 50

interface AccountFormValues {
  name: string
  brokerNote: string
}

const FIELD_NAMES: readonly (keyof AccountFormValues)[] = ['name', 'brokerNote']

/** Same rules and messages as the backend; uniqueness is checked by the backend. */
function validateName(value: unknown): string | null {
  const text = typeof value === 'string' ? value.trim() : ''
  if (text === '') return '账户名称不能为空'
  if (text.length > ACCOUNT_NAME_MAX_LENGTH)
    return `账户名称最多 ${String(ACCOUNT_NAME_MAX_LENGTH)} 个字符`
  return null
}

function validateBrokerNote(value: unknown): string | null {
  const text = typeof value === 'string' ? value.trim() : ''
  return text.length > BROKER_NOTE_MAX_LENGTH
    ? `券商备注最多 ${String(BROKER_NOTE_MAX_LENGTH)} 个字符`
    : null
}

interface AccountFormModalProps {
  /** null = new account. */
  editing: Account | null
  onDone: () => void
}

function AccountFormModal({ editing, onDone }: AccountFormModalProps) {
  const [form] = Form.useForm<AccountFormValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)

  const mutation = useMutation({
    mutationFn: (values: AccountFormValues) => {
      const request = {
        name: values.name,
        brokerNote: values.brokerNote.trim() === '' ? null : values.brokerNote,
      }
      return editing === null ? createAccount(request) : updateAccount(editing.id, request)
    },
    onSuccess: async (account) => {
      void message.success(editing === null ? `已新增账户：${account.name}` : '账户已修改')
      // The account audit belongs to that account; refresh the list and audit log.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: accountQueryKeys.all }),
        queryClient.invalidateQueries({ queryKey: auditLogQueryKeys.all }),
      ])
      onDone()
    },
    onError: (error) => {
      setSubmitError(
        applySubmitError(FIELD_NAMES, error, (fields) => {
          form.setFields(fields)
        }),
      )
    },
  })

  return (
    <Modal
      open
      title={editing === null ? '新增账户' : `修改账户 #${String(editing.id)}`}
      onCancel={onDone}
      footer={null}
      mask={{ closable: false }}
      data-testid="account-form-modal"
    >
      <Form<AccountFormValues>
        form={form}
        layout="vertical"
        initialValues={{ name: editing?.name ?? '', brokerNote: editing?.brokerNote ?? '' }}
        onFinish={(values) => {
          setSubmitError(null)
          mutation.mutate(values)
        }}
        data-testid="account-form"
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title="请勿填写账号。名称和备注只用于区分账户。"
        />
        <Form.Item label="名称" name="name" required rules={[toRule(validateName)]}>
          <Input autoComplete="off" placeholder="例如 主账户" data-testid="account-form-name" />
        </Form.Item>
        <Form.Item
          label="券商备注（可选）"
          name="brokerNote"
          rules={[toRule(validateBrokerNote)]}
          extra="自由文字，如 IBKR；不是账号"
        >
          <Input autoComplete="off" data-testid="account-form-broker-note" />
        </Form.Item>
        {submitError !== null && (
          <Form.Item>
            <Alert
              type="error"
              showIcon
              title={
                <span data-testid="account-form-error" data-error-code={submitError.code}>
                  {submitError.message}
                </span>
              }
            />
          </Form.Item>
        )}
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            loading={mutation.isPending}
            data-testid="account-form-submit"
          >
            保存
          </Button>
          <Button onClick={onDone} data-testid="account-form-cancel">
            取消
          </Button>
        </Space>
      </Form>
    </Modal>
  )
}

/**
 * 账户 page (FEAT-033): list, create and rename accounts. No delete (Q58). New accounts are not
 * switched to automatically; each row offers "切换到此账户".
 */
export function AccountsPage() {
  const { accounts, accountId, selectAccount } = useAccount()
  const { message } = App.useApp()
  // undefined = closed, null = new account.
  const [editing, setEditing] = useState<Account | null | undefined>(undefined)

  const columns: ColumnsType<Account> = [
    {
      title: '名称',
      key: 'name',
      render: (_value: unknown, account) => (
        <Space size={4}>
          <span data-testid="account-cell-name">{account.name}</span>
          {account.id === accountId && (
            <Tag color="blue" data-testid="account-cell-current">
              当前
            </Tag>
          )}
        </Space>
      ),
    },
    {
      title: '券商备注',
      key: 'brokerNote',
      render: (_value: unknown, account) => (
        <span data-testid="account-cell-broker-note">{account.brokerNote ?? EMPTY_VALUE}</span>
      ),
    },
    {
      title: '创建时间（美东）',
      key: 'createdAt',
      render: (_value: unknown, account) => (
        <span data-testid="account-cell-created">{formatDateTimeNY(account.createdAt)}</span>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_value: unknown, account) => (
        <Space size={4}>
          <Button
            size="small"
            type="link"
            onClick={() => {
              setEditing(account)
            }}
            data-testid="account-edit"
          >
            修改
          </Button>
          {account.id !== accountId && (
            <Button
              size="small"
              type="link"
              onClick={() => {
                selectAccount(account.id)
                void message.success(`已切换到账户：${account.name}`)
              }}
              data-testid="account-select"
            >
              切换到此账户
            </Button>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Card
      title="账户"
      extra={
        <Button
          type="primary"
          onClick={() => {
            setEditing(null)
          }}
          data-testid="account-create"
        >
          新增账户
        </Button>
      }
      data-testid="accounts-page"
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          每个账户的现金、交易、持仓、盈亏和审计记录互不影响。账户只记名称和券商备注，请勿填写账号。账户不能删除。
        </Typography.Text>
        <Table<Account>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={accounts}
          pagination={false}
          onRow={(account) =>
            ({
              'data-testid': 'account-row',
              'data-account-id': account.id,
            }) as HTMLAttributes<HTMLElement>
          }
        />
      </Space>
      {editing !== undefined && (
        <AccountFormModal
          key={editing?.id ?? 'new'}
          editing={editing}
          onDone={() => {
            setEditing(undefined)
          }}
        />
      )}
    </Card>
  )
}
