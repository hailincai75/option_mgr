import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { AppProviders } from './AppProviders'
import { createQueryClient } from './queryClient'

const rootElement = document.getElementById('root')
if (!rootElement) throw new Error('Root element #root not found')

const queryClient = createQueryClient()

createRoot(rootElement).render(
  <StrictMode>
    <AppProviders queryClient={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </AppProviders>
  </StrictMode>,
)
