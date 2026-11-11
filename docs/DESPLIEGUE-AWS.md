# Despliegue en AWS

La herramienta (web + backend + PostgreSQL) corre en **una instancia EC2**. La IA
local **no** corre en AWS: el backend la usa en la PC con la RTX 3070, por una red
privada de **Tailscale**.

```
Usuarios ──HTTPS──► EC2 en AWS
                     ├─ Caddy (80/443): web compilada, HTTPS, /api y /ws al backend
                     ├─ backend Spring Boot (solo red interna de Docker)
                     └─ PostgreSQL         (solo red interna de Docker)
                          │
                          │ Tailscale (cifrado, sin abrir puertos del router)
                          ▼
                     PC con la RTX 3070: Ollama en 100.83.228.76:11434
```

Archivos: `deploy/docker-compose.prod.yml`, `deploy/Caddyfile`, `deploy/.env.example`,
`backend/Dockerfile`, `frontend/Dockerfile`.

> Probado en una PC con Docker Desktop (`DOMINIO=:80`): compila, aplica las 14
> migraciones, sirve la web, pasa el WebSocket (101) y la IA responde desde el
> contenedor hacia Ollama por Tailscale. Uso de memoria: unos 700 MB en total.

---

## 0 · La PC de la IA (ya hecho el 2026-11-11)

- Tailscale instalado y conectado. IP: `tailscale ip -4` → `100.83.228.76`.
- Variable de usuario `OLLAMA_HOST=0.0.0.0:11434` y Ollama reiniciado.
- Regla de firewall **"Ollama (solo Tailscale)"**: TCP 11434 solo desde `100.64.0.0/10`.

Durante el uso: la PC prendida, sin suspensión, con Ollama y Tailscale abiertos.

## 1 · Crear la EC2 (consola web de AWS)

1. https://console.aws.amazon.com → arriba a la derecha elegir región
   **us-east-1 (N. Virginia)** (la más barata) o **sa-east-1 (São Paulo)** (más cerca).
2. **EC2 → Instancias → Lanzar instancia**:
   - Nombre: `colabmodel`
   - Imagen: **Ubuntu Server 24.04 LTS**
   - Tipo: **t3.small** (2 GB). La t3.micro (1 GB) no alcanza.
   - Par de claves: **Crear nuevo par** → nombre `colabmodel`, tipo RSA, formato `.pem`.
     Se descarga `colabmodel.pem`: guardarlo, no se puede volver a bajar.
   - Configuración de red → **Crear grupo de seguridad** con:
     - SSH (22) desde **Mi IP**
     - HTTP (80) desde cualquier lugar
     - HTTPS (443) desde cualquier lugar
   - Almacenamiento: **20 GB gp3**.
3. **Lanzar instancia**.
4. **EC2 → IP elásticas → Asignar** → después **Acciones → Asociar** a la instancia
   `colabmodel`. Esa IP ya no cambia al reiniciar. Anotarla.

## 2 · Dominio gratis (DuckDNS)

El dictado por voz del navegador **solo funciona con HTTPS**, y el HTTPS necesita un dominio.

1. https://www.duckdns.org → entrar con GitHub o Google.
2. Crear un subdominio, por ejemplo `colabmodel` → queda `colabmodel.duckdns.org`.
3. En **current ip** poner la IP elástica del paso 1 → **update ip**.

**Sin IP elástica** (la IP cambia cada vez que se detiene y arranca la instancia): la EC2
le avisa sola su IP a DuckDNS al arrancar y cada 5 minutos. Va en `~/duckdns/actualizar.sh`
(con el token de DuckDNS, solo legible por `ubuntu`) y en el `crontab` de `ubuntu`:

```bash
mkdir -p ~/duckdns && chmod 700 ~/duckdns
cat > ~/duckdns/actualizar.sh <<'EOF'
#!/bin/sh
curl -s -m 20 "https://www.duckdns.org/update?domains=<SUBDOMINIO>&token=<TOKEN>&ip=" -o "$HOME/duckdns/ultimo.log"
EOF
chmod 700 ~/duckdns/actualizar.sh
printf '%s\n' '*/5 * * * * $HOME/duckdns/actualizar.sh' '@reboot sleep 30 && $HOME/duckdns/actualizar.sh' | crontab -
cat ~/duckdns/ultimo.log   # OK = actualizado
```

## 3 · Entrar a la EC2

Desde PowerShell, en la carpeta donde está el `.pem`:

```powershell
ssh -i colabmodel.pem ubuntu@<IP-ELASTICA>
```

(La primera vez pregunta si confiar en el servidor: `yes`.)

## 4 · Preparar la EC2

```bash
# Memoria de intercambio: evita que la compilación se quede sin RAM
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Docker
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu

# Tailscale: imprime un enlace; abrirlo e iniciar sesión con LA MISMA cuenta de la PC
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up

exit   # salir y volver a entrar para que tome el grupo docker
```

Volver a entrar (`ssh -i ...`) y comprobar que la EC2 llega a la IA:

```bash
curl http://100.83.228.76:11434/api/tags
```

Tiene que listar los modelos (`qwen3:8b`, `qwen3.5:9b-q4_K_M`...). Si no responde,
ver **Problemas** abajo.

## 5 · Bajar el proyecto y configurarlo

La rama `feat/despliegue-aws` tiene que estar en GitHub (`git push -u origin feat/despliegue-aws`).

```bash
git clone -b feat/despliegue-aws https://github.com/JosecaFict/ColaboratiboDiagramador.git
cd ColaboratiboDiagramador/deploy
cp .env.example .env
openssl rand -base64 48   # copiar el resultado para DB_PASSWORD
openssl rand -base64 48   # y otro para JWT_SECRET
nano .env
```

Si el repositorio es privado, `git clone` pide usuario y contraseña: la contraseña es
un **token** de GitHub (Settings → Developer settings → Personal access tokens → con
permiso de lectura del repo), no la clave de la cuenta.

En `.env` completar: `DOMINIO`, `DB_PASSWORD`, `JWT_SECRET`, `OLLAMA_URL`
y, si se tiene, `GEMINI_API_KEY`. (Guardar en nano: `Ctrl+O`, Enter, `Ctrl+X`.)

## 6 · Levantar

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

La primera vez tarda (compila backend y frontend). Después:

```bash
docker compose -f docker-compose.prod.yml ps              # los 3 en "Up"
docker compose -f docker-compose.prod.yml logs -f backend # buscar "Started ColabModelApplication"
```

Abrir **https://colabmodel.duckdns.org**. El certificado lo saca Caddy en los
primeros segundos; si el navegador da error de certificado, esperar un minuto y recargar.

## 7 · Probar

1. Crear cuenta, crear proyecto, abrir el diagrama.
2. Dos navegadores (o uno en incógnito): lo que crea uno aparece en el otro (WebSocket).
3. Pestaña **IA** → Preguntar algo corto. La primera respuesta tarda más (Ollama carga
   el modelo en la GPU).
4. **Dictar**: el navegador pide permiso del micrófono (solo con HTTPS).

## Operación diaria

| Qué | Comando (en `~/ColaboratiboDiagramador/deploy`) |
|---|---|
| Ver estado | `docker compose -f docker-compose.prod.yml ps` |
| Ver errores | `docker compose -f docker-compose.prod.yml logs --tail 100 backend` |
| Actualizar a lo último de la rama | `git pull && docker compose -f docker-compose.prod.yml up -d --build` |
| Reiniciar | `docker compose -f docker-compose.prod.yml restart` |
| Apagar (los datos quedan) | `docker compose -f docker-compose.prod.yml down` |
| Pasar a Gemini (plan B) | en `.env`: `IA_PROVEEDOR=gemini` + `GEMINI_API_KEY`, luego `up -d` |
| Volver a la IA local | en `.env`: `IA_PROVEEDOR=ollama`, luego `up -d` |

**Costo:** detener la instancia desde la consola (**Estado de instancia → Detener**)
cuando no se use: no cobra la máquina, solo el disco (unos USD 1,6 al mes por 20 GB).
La IP pública cobra siempre, prendida o detenida (unos USD 3,6 al mes). La t3.small
prendida todo el mes son unos USD 15.

**No borrar** con `down -v`: la `-v` borra la base de datos.

## Problemas

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `curl 100.83.228.76:11434` no responde desde la EC2 | PC apagada, Ollama cerrado o Tailscale desconectado | En la PC: abrir Ollama y Tailscale; `tailscale status` en las dos máquinas |
| La IA dice que no hay conexión con Ollama | Lo mismo | Ídem; o pasar a Gemini |
| No abre el sitio | Security Group sin 80/443, o DuckDNS con otra IP | Revisar los dos |
| Error de certificado que no se va | DOMINIO en `.env` distinto del de DuckDNS | Corregir y `up -d` |
| El backend se reinicia solo | Falta memoria | `free -h`; confirmar el swap del paso 4 |
| La lectura de imágenes no lee los textos | `VISION_PROVEEDOR` sin su clave | Poner `GEMINI_API_KEY` o `ANTHROPIC_API_KEY` |

## Volver a usar todo en local

Nada de esto cambia el uso local del README (`mvn spring-boot:run` + `npm run dev`).
Para dejar la PC como antes: borrar la variable `OLLAMA_HOST`, borrar la regla de
firewall "Ollama (solo Tailscale)" y reiniciar Ollama.
