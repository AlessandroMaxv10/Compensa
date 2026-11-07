#!/usr/bin/env bash
#
#  Compensa — instalador para a sua VPS.
#  Edite as 3 linhas abaixo e rode:   sudo bash install.sh
#
# ===================  EDITE AQUI  ===================
DOMINIO="compensa.duckdns.org"      # o endereço que aponta para esta VPS
ADMIN_USER="alessandro"             # seu login de administrador
ADMIN_PASS="TROQUE_ESTA_SENHA"      # sua senha de administrador (troque!)
# ====================================================

set -e
cd "$(dirname "$0")"

echo "==> Compensa: iniciando instalação para $DOMINIO"

if [ "$ADMIN_PASS" = "TROQUE_ESTA_SENHA" ]; then
  echo "!! Edite o arquivo install.sh e troque ADMIN_PASS antes de rodar."
  exit 1
fi

# 1) Instala o Docker se não existir (funciona em Ubuntu, Debian, AlmaLinux, CentOS)
if ! command -v docker >/dev/null 2>&1; then
  echo "==> Instalando o Docker..."
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker || true
else
  echo "==> Docker já instalado."
fi

# 2) Descobre o comando de compose (plugin novo ou binário antigo)
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  echo "==> Instalando o plugin do Docker Compose..."
  curl -fsSL https://get.docker.com | sh
  COMPOSE="docker compose"
fi

# 3) Grava as configurações
cat > .env <<EOF
DOMINIO=$DOMINIO
ADMIN_USER=$ADMIN_USER
ADMIN_PASS=$ADMIN_PASS
EOF
chmod 600 .env

# 4) Abre as portas no firewall, se houver
if command -v ufw >/dev/null 2>&1; then ufw allow 80/tcp >/dev/null 2>&1 || true; ufw allow 443/tcp >/dev/null 2>&1 || true; fi
if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --permanent --add-service=http --add-service=https >/dev/null 2>&1 || true; firewall-cmd --reload >/dev/null 2>&1 || true; fi

# 5) Sobe tudo
echo "==> Subindo o servidor (pode demorar 1-2 min na primeira vez)..."
$COMPOSE up -d --build

echo ""
echo "======================================================"
echo " PRONTO! Abra no navegador:  https://$DOMINIO"
echo " Login:  $ADMIN_USER"
echo " Senha:  (a que você colocou no install.sh)"
echo ""
echo " No app, use este mesmo endereço no campo Servidor:"
echo "   https://$DOMINIO"
echo "======================================================"
echo ""
echo " Ver se está rodando:   $COMPOSE ps"
echo " Ver os registros:      $COMPOSE logs -f compensa"
echo " Parar:                 $COMPOSE down"
echo " Backup dos dados:      arquivo  server/data/db.json"
