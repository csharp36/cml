import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useApi } from '../App'
import { ConflictError } from '../api'

interface AddRepoDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export default function AddRepoDialog({ open, onOpenChange }: AddRepoDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [url, setUrl] = useState('')
  const [branch, setBranch] = useState('main')
  const [authType, setAuthType] = useState('ssh-key')
  const [keyPath, setKeyPath] = useState('~/.ssh/id_ed25519')
  const [token, setToken] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => {
      const auth: Record<string, string> = { type: authType }
      if (authType === 'ssh-key') auth.keyPath = keyPath
      if (authType === 'token') auth.token = token
      return api.post('/admin/repos', { url, branch, auth })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repos'] })
      onOpenChange(false)
      resetForm()
    },
    onError: (e) => {
      if (e instanceof ConflictError) {
        setError('Repository already exists')
      } else {
        setError(e instanceof Error ? e.message : 'Failed to add repository')
      }
    },
  })

  const resetForm = () => {
    setUrl('')
    setBranch('main')
    setAuthType('ssh-key')
    setKeyPath('~/.ssh/id_ed25519')
    setToken('')
    setError(null)
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) resetForm() }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Add Repository</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <label className="text-sm font-medium">URL</label>
            <Input
              placeholder="git@github.com:org/repo.git"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
          </div>
          <div>
            <label className="text-sm font-medium">Branch</label>
            <Input value={branch} onChange={(e) => setBranch(e.target.value)} />
          </div>
          <div>
            <label className="text-sm font-medium">Auth Type</label>
            <Select value={authType} onValueChange={setAuthType}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ssh-key">SSH Key</SelectItem>
                <SelectItem value="token">Token</SelectItem>
              </SelectContent>
            </Select>
          </div>
          {authType === 'ssh-key' && (
            <div>
              <label className="text-sm font-medium">Key Path</label>
              <Input value={keyPath} onChange={(e) => setKeyPath(e.target.value)} />
            </div>
          )}
          {authType === 'token' && (
            <div>
              <label className="text-sm font-medium">Token</label>
              <Input type="password" value={token} onChange={(e) => setToken(e.target.value)} />
            </div>
          )}
          {error && <p className="text-sm text-red-600">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => mutation.mutate()} disabled={!url || mutation.isPending}>
            {mutation.isPending ? 'Adding...' : 'Add Repository'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
