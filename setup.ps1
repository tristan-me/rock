$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath ".venv")) {
    python -m venv .venv
}

& ".\.venv\Scripts\python.exe" -m pip install --upgrade pip
& ".\.venv\Scripts\python.exe" -m pip install -r requirements.txt

if (-not (Test-Path -LiteralPath "config.local.yaml")) {
    Copy-Item -LiteralPath "config.example.yaml" -Destination "config.local.yaml"
}

Write-Host "Done. Edit config.local.yaml, then run .\run_dry.ps1"
