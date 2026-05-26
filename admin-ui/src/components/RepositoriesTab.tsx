import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { useApi } from '../App'
import AddRepoDialog from './AddRepoDialog'

interface Repo {
  name: string
  url: string
  branch: string
  fileCount: number
  lastIndexedSha: string | null
  lastIndexedAt: string | null
  status: string
}

const statusBadge = (status: string) => {
  switch (status) {
    case 'ready': return <Badge className="bg-green-100 text-green-800">ready</Badge>
    case 'cloning': return <Badge className="bg-blue-100 text-blue-800 animate-pulse">cloning</Badge>
    case 'indexing': return <Badge className="bg-yellow-100 text-yellow-800 animate-pulse">indexing</Badge>
    case 'error': return <Badge variant="destructive">error</Badge>
    default: return <Badge variant="secondary">{status}</Badge>
  }
}

export default function RepositoriesTab() {
  const api = useApi()
  const queryClient = useQueryClient()
  const [addOpen, setAddOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null)

  const { data: repos, isLoading } = useQuery<Repo[]>({
    queryKey: ['repos'],
    queryFn: () => api.get('/admin/repos'),
    refetchInterval: 5_000,
  })

  const deleteMutation = useMutation({
    mutationFn: (name: string) => api.del(`/admin/repos/${name}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repos'] })
      setDeleteTarget(null)
    },
  })

  const reindexMutation = useMutation({
    mutationFn: (name: string) => api.post(`/admin/repos/${name}/reindex`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['repos'] }),
  })

  if (isLoading) return <div className="py-4 text-gray-500">Loading repositories...</div>

  return (
    <div className="space-y-4 py-4">
      <div className="flex justify-between items-center">
        <h2 className="text-lg font-semibold">Repositories</h2>
        <Button onClick={() => setAddOpen(true)}>Add Repository</Button>
      </div>

      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>URL</TableHead>
                <TableHead>Branch</TableHead>
                <TableHead>Files</TableHead>
                <TableHead>Last Indexed</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {repos?.map((repo) => (
                <TableRow key={repo.name}>
                  <TableCell className="font-medium">{repo.name}</TableCell>
                  <TableCell className="text-sm text-gray-500 max-w-xs truncate">{repo.url}</TableCell>
                  <TableCell>{repo.branch}</TableCell>
                  <TableCell>{repo.fileCount}</TableCell>
                  <TableCell className="text-sm text-gray-500">
                    {repo.lastIndexedAt ? new Date(repo.lastIndexedAt).toLocaleString() : '—'}
                  </TableCell>
                  <TableCell>{statusBadge(repo.status)}</TableCell>
                  <TableCell>
                    <div className="flex gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => reindexMutation.mutate(repo.name)}
                        disabled={repo.status === 'cloning' || repo.status === 'indexing'}
                      >
                        Reindex
                      </Button>
                      <Button
                        variant="destructive"
                        size="sm"
                        onClick={() => setDeleteTarget(repo.name)}
                      >
                        Delete
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {repos?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-center text-gray-500">
                    No repositories. Click "Add Repository" to get started.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <AddRepoDialog open={addOpen} onOpenChange={setAddOpen} />

      {/* Delete confirmation dialog */}
      <Dialog open={!!deleteTarget} onOpenChange={(o) => !o && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Repository</DialogTitle>
          </DialogHeader>
          <p>Are you sure you want to delete <strong>{deleteTarget}</strong>? This removes all indexed data and the disk clone.</p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>Cancel</Button>
            <Button
              variant="destructive"
              onClick={() => deleteTarget && deleteMutation.mutate(deleteTarget)}
              disabled={deleteMutation.isPending}
            >
              {deleteMutation.isPending ? 'Deleting...' : 'Delete'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
