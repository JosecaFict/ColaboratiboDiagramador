import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import Raiz from './Raiz'
import './styles.css'
import { iniciarTema } from './apariencia/useTema'
iniciarTema()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Raiz />
  </StrictMode>
)
