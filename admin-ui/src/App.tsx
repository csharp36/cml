import { useState, createContext, useContext } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { createApi, UnauthorizedError, ServiceUnavailableError } from './api'
import type { Api } from './api'
import DashboardTab from './components/DashboardTab'
import RepositoriesTab from './components/RepositoriesTab'
import EventsTab from './components/EventsTab'

const ApiContext = createContext<Api | null>(null)
export const useApi = () => {
  const api = useContext(ApiContext)
  if (!api) throw new Error('useApi must be used within ApiContext')
  return api
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
})

function App() {
  const [api, setApi] = useState<Api | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tokenInput, setTokenInput] = useState('')

  const handleConnect = async () => {
    setError(null)
    const newApi = createApi(tokenInput)
    try {
      await newApi.get('/admin/health')
      setApi(newApi)
    } catch (e) {
      if (e instanceof ServiceUnavailableError) {
        setError('Admin API disabled — no token configured on server')
      } else if (e instanceof UnauthorizedError) {
        setError('Invalid admin token')
      } else {
        setError('Failed to connect: ' + (e instanceof Error ? e.message : 'Unknown error'))
      }
    }
  }

  const handleDisconnect = () => {
    setApi(null)
    setTokenInput('')
    queryClient.clear()
  }

  if (!api) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <Card className="w-96">
          <CardHeader>
            <CardTitle>Source Code Indexer Admin</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              <Input
                type="password"
                placeholder="Admin token"
                value={tokenInput}
                onChange={(e) => setTokenInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleConnect()}
              />
              {error && <p className="text-sm text-red-600">{error}</p>}
              <Button onClick={handleConnect} className="w-full" disabled={!tokenInput}>
                Connect
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <QueryClientProvider client={queryClient}>
      <ApiContext.Provider value={api}>
        <div className="min-h-screen bg-gray-50">
          <header className="bg-white border-b px-6 py-3 flex items-center justify-between">
            <h1 className="text-lg font-semibold">Source Code Indexer Admin</h1>
            <Button variant="outline" size="sm" onClick={handleDisconnect}>
              Disconnect
            </Button>
          </header>
          <main className="p-6">
            <Tabs defaultValue="dashboard">
              <TabsList>
                <TabsTrigger value="dashboard">Dashboard</TabsTrigger>
                <TabsTrigger value="repositories">Repositories</TabsTrigger>
                <TabsTrigger value="events">Events</TabsTrigger>
              </TabsList>
              <TabsContent value="dashboard"><DashboardTab /></TabsContent>
              <TabsContent value="repositories"><RepositoriesTab /></TabsContent>
              <TabsContent value="events"><EventsTab /></TabsContent>
            </Tabs>
          </main>
        </div>
      </ApiContext.Provider>
    </QueryClientProvider>
  )
}

export default App
