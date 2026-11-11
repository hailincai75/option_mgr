import { Alert, Button } from 'antd'
import { ApiError } from '../api/client'

function describeError(error: Error): string {
  if (error instanceof ApiError) return `${error.message} (${error.code})`
  return error.message
}

interface QueryErrorProps {
  title: string
  error: Error
  retrying: boolean
  onRetry: () => void
  /** Prefix for `-error` / `-retry` test ids. */
  testIdPrefix: string
}

/** Error state for a failed query: message + retry button. */
export function QueryError({ title, error, retrying, onRetry, testIdPrefix }: QueryErrorProps) {
  return (
    <Alert
      type="error"
      showIcon
      title={title}
      description={<span data-testid={`${testIdPrefix}-error`}>{describeError(error)}</span>}
      action={
        <Button data-testid={`${testIdPrefix}-retry`} loading={retrying} onClick={onRetry}>
          重试
        </Button>
      }
    />
  )
}
