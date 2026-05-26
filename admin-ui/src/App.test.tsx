import { render, screen } from '@testing-library/react'
import App from './App'

describe('App', () => {
  it('renders placeholder text', () => {
    render(<App />)
    expect(screen.getByText(/Admin UI/)).toBeInTheDocument()
  })
})
