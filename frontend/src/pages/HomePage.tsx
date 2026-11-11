import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Spin, Tag } from 'antd'
import { ApiError } from '../api/client'
import { getHealth, healthQueryKey } from '../api/health'
import { formatInstantNY } from '../utils/format'

function describeError(error: Error): string {
  if (error instanceof ApiError) return `${error.message} (${error.code})`
  return error.message
}

function HealthCard() {
  const { data, error, isPending, isFetching, refetch } = useQuery({
    queryKey: healthQueryKey,
    queryFn: ({ signal }) => getHealth(signal),
  })

  if (isPending) {
    return (
      <div data-testid="health-loading" style={{ padding: 24, textAlign: 'center' }}>
        <Spin />
      </div>
    )
  }

  if (error) {
    return (
      <Alert
        type="error"
        showIcon
        title="Backend is unavailable"
        description={<span data-testid="health-error">{describeError(error)}</span>}
        action={
          <Button
            data-testid="health-retry"
            loading={isFetching}
            onClick={() => {
              void refetch()
            }}
          >
            Retry
          </Button>
        }
      />
    )
  }

  return (
    <Descriptions column={1} bordered size="small">
      <Descriptions.Item label="Status">
        <Tag color={data.status === 'UP' ? 'success' : 'error'} data-testid="health-status">
          {data.status}
        </Tag>
      </Descriptions.Item>
      <Descriptions.Item label="Version">
        <span data-testid="health-version">{data.version}</span>
      </Descriptions.Item>
      <Descriptions.Item label="Server time (New York)">
        <span data-testid="health-time">{formatInstantNY(data.time)}</span>
      </Descriptions.Item>
    </Descriptions>
  )
}

export function HomePage() {
  return (
    <Card title="Backend status" style={{ maxWidth: 560 }} data-testid="health-card">
      <HealthCard />
    </Card>
  )
}
