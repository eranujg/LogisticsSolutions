#!/usr/bin/env bash
# Run inside Ubuntu (WSL):  bash scripts/setup-wsl.sh
# Safe to re-run: each step skips what is already installed.
set -eo pipefail
export PAGER=cat

sudo apt-get update && sudo apt-get upgrade -y
sudo apt-get install -y build-essential curl wget unzip zip git jq ca-certificates

# Java 25 (Eclipse Temurin) via SDKMAN
if [ ! -d "$HOME/.sdkman" ]; then curl -s "https://get.sdkman.io" | bash; fi
sed -i 's/sdkman_auto_answer=false/sdkman_auto_answer=true/' "$HOME/.sdkman/etc/config"
source "$HOME/.sdkman/bin/sdkman-init.sh"
JAVA_ID=$(sdk list java | grep -Eo '25(\.[0-9]+)*-tem' | head -1)
if [ -z "$JAVA_ID" ]; then echo "Could not find a Java 25 Temurin build"; exit 1; fi
sdk install java "$JAVA_ID" || true
sdk default java "$JAVA_ID"

# Node.js LTS via nvm, then Angular, Ionic and AWS CDK CLIs
export NVM_DIR="$HOME/.nvm"
if [ ! -d "$NVM_DIR" ]; then curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash; fi
source "$NVM_DIR/nvm.sh"
nvm install --lts
nvm alias default 'lts/*'
npm install -g @angular/cli @ionic/cli aws-cdk

# Python 3.13 via uv
if ! command -v uv >/dev/null 2>&1 && [ ! -x "$HOME/.local/bin/uv" ]; then curl -LsSf https://astral.sh/uv/install.sh | sh; fi
export PATH="$HOME/.local/bin:$PATH"
uv python install 3.13

# AWS CLI v2 (used later for staging)
if ! command -v aws >/dev/null 2>&1; then
  curl -s "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
  unzip -qo /tmp/awscliv2.zip -d /tmp && sudo /tmp/aws/install --update
fi

# Secret scanning before every commit
uv tool install pre-commit || true
if ! command -v gitleaks >/dev/null 2>&1; then
  GL_VER=$(curl -s https://api.github.com/repos/gitleaks/gitleaks/releases/latest | jq -r .tag_name | sed 's/^v//')
  curl -sL "https://github.com/gitleaks/gitleaks/releases/download/v${GL_VER}/gitleaks_${GL_VER}_linux_x64.tar.gz" | sudo tar -xz -C /usr/local/bin gitleaks
fi

# SSH key for GitHub
[ -f "$HOME/.ssh/id_ed25519" ] || ssh-keygen -t ed25519 -C "github" -f "$HOME/.ssh/id_ed25519" -N ""

mkdir -p "$HOME/projects"
echo; echo "=== Installed ==="
java -version 2>&1 | head -1
echo "Node $(node -v)"
uv python list --only-installed | head -1
aws --version
gitleaks version
echo; echo "Add this key to GitHub (Settings > SSH and GPG keys):"
cat "$HOME/.ssh/id_ed25519.pub"
