import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useApi } from '../App'

interface IndexingEvent {
  id: number
  repoName: string
  eventType: string
  status: string
  errorMessage: string | null
  createdAt: string
}

const statusBadge = (status: string) => {
  switch (status) {
    case 'pending': return <Badge variant="secondary">pending</Badge>
    case 'processing': return <Badge className="bg-blue-100 text-blue-800">processing</Badge>
    case 'completed': return <Badge className="bg-green-100 text-green-800">completed</Badge>
    case 'failed': return <Badge variant="destructive">failed</Badge>
    default: return <Badge variant="secondary">{status}</Badge>
  }
}

export default function EventsTab() {
  const api = useApi()
  const queryClient = useQueryClient()
  const [repo, setRepo] = useState('')
  const [status, setStatus] = useState('all')
  const [since, setSince] = useState('')
  const [limit, setLimit] = useState(50)

  const queryParams = new URLSearchParams()
  if (repo) queryParams.set('repo', repo)
  if (status !== 'all') queryParams.set('status', status)
  if (since) queryParams.set('since', new Date(since).toISOString())
  queryParams.set('limit', String(limit))

  const { data: events, isLoading } = useQuery<IndexingEvent[]>({
    queryKey: ['events', repo, status, since, limit],
    queryFn: () => api.get(`/admin/events?${queryParams.toString()}`),
    refetchInterval: 10_000,
  })

  const retryMutation = useMutation({
    mutationFn: (id: number) => api.post(`/admin/events/${id}/retry`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['events'] }),
  })

  return (
    <div className="space-y-4 py-4">
      <h2 className="text-lg font-semibold">Events</h2>

      {/* Filter bar */}
      <div className="flex gap-4 items-end">
        <div>
          <label className="text-sm font-medium">Repository</label>
          <Input
            placeholder="Filter by repo..."
            value={repo}
            onChange={(e) => setRepo(e.target.value)}
            className="w-48"
          />
        </div>
        <div>
          <label className="text-sm font-medium">Status</label>
          <Select value={status} onValueChange={setStatus}>
            <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All</SelectItem>
              <SelectItem value="pending">Pending</SelectItem>
              <SelectItem value="processing">Processing</SelectItem>
              <SelectItem value="completed">Completed</SelectItem>
              <SelectItem value="failed">Failed</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div>
          <label className="text-sm font-medium">Since</label>
          <Input
            type="datetime-local"
            value={since}
            onChange={(e) => setSince(e.target.value)}
            className="w-52"
          />
        </div>
        <div>
          <label className="text-sm font-medium">Limit</label>
          <Input
            type="number"
            value={limit}
            onChange={(e) => setLimit(Number(e.target.value) || 50)}
            className="w-24"
          />
        </div>
      </div>

      {isLoading ? (
        <div className="py-4 text-gray-500">Loading events...</div>
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>ID</TableHead>
                  <TableHead>Repository</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Error</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {events?.map((event) => (
                  <TableRow key={event.id}>
                    <TableCell className="font-mono text-sm">{event.id}</TableCell>
                    <TableCell className="font-medium">{event.repoName}</TableCell>
                    <TableCell>{event.eventType}</TableCell>
                    <TableCell>{statusBadge(event.status)}</TableCell>
                    <TableCell className="text-sm text-red-600 max-w-xs truncate" title={event.errorMessage || ''}>
                      {event.errorMessage || '—'}
                    </TableCell>
                    <TableCell className="text-sm text-gray-500">
                      {new Date(event.createdAt).toLocaleString()}
                    </TableCell>
                    <TableCell>
                      {event.status === 'failed' && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => retryMutation.mutate(event.id)}
                          disabled={retryMutation.isPending}
                        >
                          Retry
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
                {events?.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={7} className="text-center text-gray-500">
                      No events match the current filters
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
