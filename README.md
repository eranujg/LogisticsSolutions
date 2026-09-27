# Transport Platform: Local Setup (Windows + WSL2)

1. Run scripts/setup-windows.ps1 in PowerShell as Administrator, then restart.
2. Copy scripts/.wslconfig to C:\Users\<you>\.wslconfig and run `wsl --shutdown`.
3. 8 GB laptop: install Docker Engine inside Ubuntu instead of Docker Desktop (see chat steps).
4. Open Ubuntu and run `bash scripts/setup-wsl.sh`; add the printed SSH key to GitHub.
5. Keep the project inside WSL (~/projects/transport), not on C:\ (much faster).
6. `cp .env.example .env` and set strong passwords.
7. `docker compose up -d --build` (core only; add `--profile auth`, `--profile files`, `--profile queue` when needed)
8. Inside your Git repository: `pre-commit install` (run `pre-commit autoupdate` once).

| Service | Address |
|---|---|
| PostgreSQL | localhost:5432 |
| Keycloak admin | http://localhost:8180 |
| Valkey | localhost:6379 |
| S3 (S3Mock) | http://localhost:9090 |
| SQS (ElasticMQ) | http://localhost:9324, UI http://localhost:9325 |
| Gotenberg PDF | http://localhost:3000 |
| Mailpit inbox | http://localhost:8025 |
