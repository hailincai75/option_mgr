import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import enUS from 'antd/locale/en_US'
import type { ReactNode } from 'react'

interface AppProvidersProps {
  queryClient: QueryClient
  children: ReactNode
}

/** Global providers shared by the app and by component tests (router is supplied separately). */
export function AppProviders({ queryClient, children }: AppProvidersProps) {
  return (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={enUS}>
        <AntdApp>{children}</AntdApp>
      </ConfigProvider>
    </QueryClientProvider>
  )
}
