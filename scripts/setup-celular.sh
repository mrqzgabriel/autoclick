#!/usr/bin/env bash
# Prepara um celular pelo cabo (depuração USB ligada) pra receber as
# atualizações do servidor sozinho. Roda UMA vez por celular; depois disso o
# app se atualiza sem cabo.
#
#   scripts/setup-celular.sh https://autoclick-xxxx.easypanel.host
#   scripts/setup-celular.sh https://... SERIAL     (com mais de um celular no cabo)
#
# O que ele faz:
#  1. instala o APK mais novo (apk/AutoClick.apk, ou o AutoClick-v*.apk mais recente)
#  2. libera "configurações restritas" (a chave de acessibilidade fica cinza sem isso)
#  3. libera "instalar apps desconhecidos" pro AutoClick = atualização sem confirmação
#  4. grava o endereço do servidor no app e força a primeira sincronização
#
# O que NÃO dá pra fazer pelo cabo no HyperOS (fica manual, uma vez):
#  - ligar o AutoClick em Configurações > Acessibilidade
#  - Autostart e bateria "Sem restrições" (Configurações > Apps > AutoClick)
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER="${1:-}"
SERIAL="${2:-}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=com.gm.autoclick

if [ -z "$SERVER" ]; then
  echo "uso: $0 https://endereco-do-servidor [SERIAL]"; exit 1
fi
if [ -n "$SERIAL" ]; then ADB="$ADB -s $SERIAL"; fi

echo "== celular =="
$ADB get-state >/dev/null 2>&1 || { echo "nenhum celular autorizado no cabo (ligue a depuração USB e aceite o aviso)"; exit 1; }
$ADB shell getprop ro.product.model
INSTALLED=$($ADB shell dumpsys package $PKG 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1 || true)
echo "versão instalada: ${INSTALLED:-nenhuma}"

APK=apk/AutoClick.apk
if [ ! -f "$APK" ]; then
  APK=$(ls -t AutoClick-v*.apk 2>/dev/null | head -1 || true)
fi
[ -f "${APK:-}" ] || { echo "nenhum APK: rode scripts/build-apk.sh antes"; exit 1; }
echo "== instalando $APK =="
$ADB install -r "$APK"

echo "== permissões =="
$ADB shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow || true
$ADB shell appops set $PKG REQUEST_INSTALL_PACKAGES allow
echo "instalar apps desconhecidos: $($ADB shell appops get $PKG REQUEST_INSTALL_PACKAGES | tail -1)"

echo "== servidor =="
# espera o serviço subir depois da instalação, senão o comando chega cedo demais
sleep 4
$ADB shell am start -n $PKG/.MainActivity --es server "$SERVER" >/dev/null
sleep 6
$ADB logcat -d -s AutoClick | grep -i "sync\|servidor" | tail -5 || true

cat <<EOF

== falta fazer NA MÃO (uma vez) ==
1. Configurações > Acessibilidade > Apps baixados > AutoClick > ligar
   (se estiver cinza: Configurações > Apps > Gerenciar apps > AutoClick > ⋮ > Permitir configurações restritas)
2. Configurações > Apps > AutoClick > Autostart LIGADO e Economia de bateria = Sem restrições
3. Abra o AutoClick e confira no card "Servidor": Sincronização: ... ok
EOF
