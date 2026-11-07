#!/usr/bin/env bash
#
#  Compensa — instalação ao lado do MaxV10 (HTTPS na porta 8443, via DuckDNS).
#  Não encosta nas portas 80/443 que o MaxV10 usa.
#
#  Edite as 5 linhas abaixo e rode:   sudo bash install-https.sh
#
# ===================  EDITE AQUI  ===================
DOMINIO="compensa.duckdns.org"      # o subdomínio DuckDNS que você criou
DUCKDNS_TOKEN="SEU_TOKEN_DUCKDNS"   # o token da sua conta DuckDNS (o mesmo do MaxV10)
ADMIN_USER="alessandro"             # seu login de administrador
ADMIN_PASS="TROQUE_ESTA_SENHA"      # sua senha de administrador (troque!)
PORTA="8443"                        # porta do Compensa (deixe 8443)
# ====================================================

set -e
cd "$(dirname "$0")"
echo "==> Compensa: instalando em https://$DOMINIO:$PORTA (ao lado do MaxV10)"

if [ "$ADMIN_PASS" = "TROQUE_ESTA_SENHA" ] || [ "$DUCKDNS_TOKEN" = "SEU_TOKEN_DUCKDNS" ]; then
  echo "!! Edite install-https.sh e preencha DUCKDNS_TOKEN e ADMIN_PASS antes de rodar."
  exit 1
fi

# Docker
if ! command -v docker >/dev/null 2>&1; then
  echo "==> Instalando o Docker..."
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker || true
fi
if docker compose version >/dev/null 2>&1; then COMPOSE="docker compose"; else COMPOSE="docker-compose"; fi

# Configurações
cat > .env <<EOF
DOMINIO=$DOMINIO
DUCKDNS_TOKEN=$DUCKDNS_TOKEN
ADMIN_USER=$ADMIN_USER
ADMIN_PASS=$ADMIN_PASS
EOF
chmod 600 .env

# Abre só a porta do Compensa no firewall (não mexe na 80/443)
if command -v ufw >/dev/null 2>&1; then ufw allow ${PORTA}/tcp >/dev/null 2>&1 || true; fi
if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --permanent --add-port=${PORTA}/tcp >/dev/null 2>&1 || true; firewall-cmd --reload >/dev/null 2>&1 || true; fi

echo "==> Subindo o Compensa (a primeira vez compila o Caddy, pode levar 2-4 min)..."
$COMPOSE -f docker-compose.https.yml up -d --build

echo ""
echo "======================================================"
echo " PRONTO! Abra no navegador:  https://$DOMINIO:$PORTA"
echo " Login: $ADMIN_USER   /   Senha: (a do install-https.sh)"
echo ""
echo " No app, no campo Servidor, use:"
echo "   https://$DOMINIO:$PORTA"
echo "======================================================"
echo " O MaxV10 continua intocado nas portas 80 e 443."
echo ""
echo " Ver registros:   $COMPOSE -f docker-compose.https.yml logs -f"
echo " Parar:           $COMPOSE -f docker-compose.https.yml down"
echo " Backup:          arquivo  server/data/db.json"
