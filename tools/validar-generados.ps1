param(
    [Parameter(Mandatory = $true)][ValidateSet('postgres', 'mongo')][string]$Almacen,
    [string]$Raiz = "$PSScriptRoot/../backend/target/revision-generados"
)
$ErrorActionPreference = 'Stop'
$carpeta = (Resolve-Path -LiteralPath (Join-Path $Raiz $Almacen)).Path
$resultados = @()
foreach ($proyecto in Get-ChildItem -LiteralPath $carpeta -Directory) {
    if (-not (Test-Path -LiteralPath (Join-Path $proyecto.FullName 'pom.xml'))) { continue }
    $log = Join-Path $carpeta ($proyecto.Name + '-package.log')
    # Windows PowerShell convierte stderr de Java (incluso avisos de Mockito)
    # en NativeCommandError. El resultado de Maven lo decide su codigo de salida.
    $preferenciaAnterior = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & mvn --batch-mode -q -f (Join-Path $proyecto.FullName 'pom.xml') package *> $log
        $codigoMaven = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preferenciaAnterior
    }
    $resultado = [PSCustomObject]@{ almacen = $Almacen; modelo = $proyecto.Name; exitCode = $codigoMaven; log = $log }
    $resultados += $resultado
    $resultado | ConvertTo-Json -Compress
}
$resultados | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $carpeta 'resultados.json') -Encoding UTF8
if ($resultados.Count -ne 9 -or @($resultados | Where-Object exitCode -ne 0).Count -gt 0) { exit 1 }
