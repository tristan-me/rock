$ErrorActionPreference = "Stop"

$python = "python"
if (Test-Path -LiteralPath ".\.venv\Scripts\python.exe") {
    $python = ".\.venv\Scripts\python.exe"
}

& $python -m rock_catcher.run --config config.local.yaml --dry-run --preview
