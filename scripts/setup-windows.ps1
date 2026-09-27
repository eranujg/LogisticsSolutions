# Run in PowerShell as Administrator. Restart Windows when WSL asks.
wsl --install -d Ubuntu-24.04

winget install --id Git.Git -e
winget install --id Microsoft.WindowsTerminal -e
winget install --id Microsoft.VisualStudioCode -e
winget install --id Docker.DockerDesktop -e
winget install --id GitHub.cli -e

Write-Host "Done. Next: copy .wslconfig to $env:USERPROFILE, restart, open Docker Desktop,"
Write-Host "enable 'Use WSL 2 based engine' and WSL integration for Ubuntu-24.04."
