import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import Raiz from './Raiz'
import './styles.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Raiz />
  </StrictMode>
)
