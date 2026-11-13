import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Modal, Space, Typography } from 'antd'
import { useState } from 'react'
import { ApiError } from '../api/client'
import { refreshAfterChange } from './trade/tradeFormSupport'

interface DeleteConfirmModalProps {
  title: string
  /** What is being deleted, e.g. "STO 1 张 AAPL 2026-12-18 200 P，2026-11-04 11:00，+360.00". */
  summary: string
  onConfirm: () => Promise<void>
  onDone: () => void
  /** `trade-delete` or `transfer-delete`. */
  testIdPrefix: string
  /** Warning under the summary (default "删除后不可恢复，会写入审计记录。"). */
  warning?: string
}

const DEFAULT_WARNING = '删除后不可恢复，会写入审计记录。'

/**
 * Delete confirmation (FEAT-005). Shows the summary and a warning (default
 * "删除后不可恢复，会写入审计记录。"). On a rejection the backend message is shown verbatim and the
 * dialog stays open; on success every affected query refreshes and the dialog closes.
 */
export function DeleteConfirmModal({
  title,
  summary,
  onConfirm,
  onDone,
  testIdPrefix,
  warning = DEFAULT_WARNING,
}: DeleteConfirmModalProps) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<{ code: string; message: string } | null>(null)
  const mutation = useMutation({
    mutationFn: onConfirm,
    onSuccess: async () => {
      await refreshAfterChange(queryClient)
      onDone()
    },
    onError: (failure) => {
      setError({
        code: failure instanceof ApiError ? failure.code : 'UNKNOWN',
        message: failure.message,
      })
    },
  })

  return (
    <Modal open title={title} onCancel={onDone} footer={null} data-testid={`${testIdPrefix}-modal`}>
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text strong data-testid={`${testIdPrefix}-summary`}>
          {summary}
        </Typography.Text>
        <Typography.Text type="danger" data-testid={`${testIdPrefix}-warning`}>
          {warning}
        </Typography.Text>
        {error !== null && (
          <Alert
            type="error"
            showIcon
            title={
              <span data-testid={`${testIdPrefix}-error`} data-error-code={error.code}>
                {error.message}
              </span>
            }
          />
        )}
        <Space>
          <Button
            danger
            type="primary"
            loading={mutation.isPending}
            onClick={() => {
              setError(null)
              mutation.mutate()
            }}
            data-testid={`${testIdPrefix}-confirm`}
          >
            确认删除
          </Button>
          <Button onClick={onDone} data-testid={`${testIdPrefix}-cancel`}>
            取消
          </Button>
        </Space>
      </Space>
    </Modal>
  )
}
