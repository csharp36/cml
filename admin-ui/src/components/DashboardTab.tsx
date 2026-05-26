import { useQuery } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { useApi } from '../App'

interface HealthData {
  repositories: Array<{
    repo_name: string
    last_indexed_sha: string | null
    pending_events: number
    failed_events: number
  }>
  totalPendingEvents: number
  totalFailedEvents: number
  recentFailures: Array<{
    repo_name: string
    error_message: string
    created_at: string
  }>
}

export default function DashboardTab() {
  const api = useApi()
  const { data, isLoading, error } = useQuery<HealthData>({
    queryKey: ['health'],
    queryFn: () => api.get('/admin/health'),
    refetchInterval: 10_000,
  })

  if (isLoading) return <div className="py-4 text-gray-500">Loading health data...</div>
  if (error) return <div className="py-4 text-red-600">Failed to load health data</div>
  if (!data) return null

  return (
    <div className="space-y-6 py-4">
      {/* Summary cards */}
      <div className="grid grid-cols-3 gap-4">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-gray-500">Repositories</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold">{data.repositories.length}</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-gray-500">Pending Events</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold">{data.totalPendingEvents}</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-gray-500">Failed Events</CardTitle>
          </CardHeader>
          <CardContent>
            <p className={`text-2xl font-bold ${data.totalFailedEvents > 0 ? 'text-red-600' : ''}`}>
              {data.totalFailedEvents}
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Repository status */}
      <Card>
        <CardHeader>
          <CardTitle>Repository Status</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Last Indexed SHA</TableHead>
                <TableHead>Pending</TableHead>
                <TableHead>Failed</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.repositories.map((repo) => (
                <TableRow key={repo.repo_name} className={repo.failed_events > 0 ? 'bg-red-50' : ''}>
                  <TableCell className="font-medium">{repo.repo_name}</TableCell>
                  <TableCell className="font-mono text-sm">
                    {repo.last_indexed_sha?.slice(0, 7) || '—'}
                  </TableCell>
                  <TableCell>{repo.pending_events}</TableCell>
                  <TableCell>
                    {repo.failed_events > 0 ? (
                      <Badge variant="destructive">{repo.failed_events}</Badge>
                    ) : (
                      repo.failed_events
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {data.repositories.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="text-center text-gray-500">
                    No repositories indexed
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Recent failures */}
      {data.recentFailures.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Recent Failures</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Repository</TableHead>
                  <TableHead>Error</TableHead>
                  <TableHead>Time</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.recentFailures.map((f, i) => (
                  <TableRow key={i}>
                    <TableCell className="font-medium">{f.repo_name}</TableCell>
                    <TableCell className="text-sm text-red-600 max-w-md truncate">
                      {f.error_message}
                    </TableCell>
                    <TableCell className="text-sm text-gray-500">
                      {new Date(f.created_at).toLocaleString()}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
