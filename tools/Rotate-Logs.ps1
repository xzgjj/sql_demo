param(
    [string]$LogDir = "logs",
    [int]$MaxFilesPerPrefix = 10,
    [int]$MaxSizeMB = 20,
    [switch]$Apply
)

$root = Resolve-Path "."
$target = Join-Path $root $LogDir

if (-not (Test-Path -LiteralPath $target)) {
    New-Item -ItemType Directory -Path $target -Force | Out-Null
}

$maxBytes = $MaxSizeMB * 1MB
$files = Get-ChildItem -Path $target -File -Filter "*.log" | Sort-Object LastWriteTime -Descending

foreach ($file in $files) {
    if ($file.Length -gt $maxBytes) {
        Write-Output "oversize`t$($file.Name)`t$([math]::Round($file.Length / 1MB, 2))MB"
    }
}

$groups = $files | Group-Object {
    $name = $_.Name
    if ($name -match '^(.*?)-\d{8,}') { $matches[1] } else { $name -replace '\.(out|err)\.log$', '' }
}

foreach ($group in $groups) {
    $overflow = $group.Group | Sort-Object LastWriteTime -Descending | Select-Object -Skip $MaxFilesPerPrefix
    foreach ($file in $overflow) {
        Write-Output "overflow`t$($group.Name)`t$($file.Name)"
        if ($Apply) {
            $archive = Join-Path $target "archive"
            if (-not (Test-Path -LiteralPath $archive)) {
                New-Item -ItemType Directory -Path $archive -Force | Out-Null
            }
            Move-Item -LiteralPath $file.FullName -Destination (Join-Path $archive $file.Name) -Force
        }
    }
}
