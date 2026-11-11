import { Button, Result } from 'antd'
import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <Result
      status="404"
      title="Page not found"
      data-testid="not-found"
      extra={
        <Link to="/">
          <Button type="primary">Back to home</Button>
        </Link>
      }
    />
  )
}
