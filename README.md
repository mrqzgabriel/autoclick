# AutoClick

App Android que grava toques e repete em loop (macro), mais um **servidor** que
roda no EasyPanel e mantém todos os celulares iguais: mesma versão do app,
mesmos macros, mesmas configurações. Você mexe no repositório, faz deploy, e os
celulares se atualizam sozinhos em até 5 minutos.

```
autoclick/
├── app/            código do app Android (Kotlin)
├── macros/         macros publicados: um .json por macro (formato exportado pelo app)
├── config.json     configurações que valem pra todos os celulares
├── server/         servidor Node (sem dependências) + painel de status
├── Dockerfile      compila o APK e sobe o servidor, tudo no deploy do EasyPanel
├── scripts/        build local e preparação de celular pelo cabo
└── autoclick-keystore.jks   chave de assinatura (NUNCA apagar, NUNCA tornar o repositório público)
```

## Como a atualização automática funciona

Cada celular fala com o servidor a cada 5 minutos (`POST /api/sync`). Manda um
relatório (versão, macro rodando, bateria) e recebe três coisas:

| O que | De onde vem | Como chega no celular |
|---|---|---|
| **App (APK)** | compilado no deploy a partir de `app/` | baixa e **instala sozinho**, sem ninguém tocar (Android 12+) |
| **Macros** | `macros/*.json` | instala/atualiza/remove no celular, reescalando toques se a tela for outra |
| **Configurações** | `config.json` | intervalo, horário de silêncio, macro da bolha, autorun, comandos |

- O número da versão do app (`versionCode`) é gerado no build a partir da
  hora. **Não precisa mexer em versão nenhuma**: mudou algo em `app/`, o deploy
  gera um APK maior e os celulares trocam. Mudou só macro/config, o Docker
  reaproveita o APK do cache e ninguém reinstala nada à toa.
- A instalação espera o macro terminar a passada (só instala parado ou na
  espera entre passadas). Depois, o Android religa o serviço de acessibilidade
  e o macro continua de onde estava.
- A assinatura tem que ser sempre a mesma (`autoclick-keystore.jks`). Se a
  chave mudar, o Android recusa instalar por cima e todo celular teria que ser
  refeito na mão.

## 1. Colocar no EasyPanel (uma vez)

1. No EasyPanel, **Create Service → App**. Nome: `autoclick`.
2. **Source → GitHub**: repositório `mrqzgabriel/autoclick`, branch `main`.
3. **Build → Dockerfile** (caminho `Dockerfile`, o padrão).
4. **Domains**: adicione um domínio (o do EasyPanel serve, ex.
   `autoclick-xxxx.easypanel.host`), **porta 3000**, HTTPS ligado.
5. **Endereço e token**: edite `gradle.properties` no repositório e faça
   commit (é a forma mais simples e que nunca dá conflito, porque esses dois
   valores precisam ficar gravados dentro do APK no build):
   - `autoclick.serverUrl=https://autoclick-xxxx.easypanel.host` (o endereço do
     passo 4; assim um celular novo já nasce sabendo onde sincronizar)
   - `autoclick.syncToken=algumaSenha` (opcional; o app manda em cada pedido e o
     servidor recusa quem não mandar. O painel continua aberto.)
6. **Mounts** (opcional): volume em `/app/data` pra lista de celulares
   sobreviver ao deploy. Sem isso eles reaparecem sozinhos em 5 min.
7. **Deploy**. O primeiro build demora (baixa o SDK do Android, uns 5 a 10
   min). Os seguintes usam o cache e levam 1 a 3 min.
8. Abra o domínio: o **painel** mostra a versão publicada e cada celular.

> As variáveis de ambiente `SERVER_URL`/`SYNC_TOKEN` do EasyPanel também
> funcionam, mas só se o EasyPanel as repassar como *build args* — e o token
> precisa ser o **mesmo** no build (APK) e no runtime (servidor). Por isso o
> caminho recomendado é `gradle.properties`, que vale pros dois de uma vez.

## 2. Primeira instalação em cada celular (uma vez, pelo cabo)

A versão que está nos celulares hoje (1.11.3) **não tem o atualizador**, então
a primeira troca é pelo cabo. Depois dela, nunca mais.

```bash
scripts/build-apk.sh                                   # gera apk/AutoClick.apk
scripts/setup-celular.sh https://autoclick-xxxx.easypanel.host          # 1 celular
scripts/setup-celular.sh https://autoclick-xxxx.easypanel.host SERIAL   # vários no cabo
```

O script instala o APK, libera "configurações restritas" e "instalar apps
desconhecidos" pro AutoClick, grava o servidor e força a primeira
sincronização. Na mão (o HyperOS não deixa pelo cabo): ligar o AutoClick em
**Acessibilidade**, e em Configurações > Apps > AutoClick deixar **Autostart
ligado** e bateria **Sem restrições**.

Sem cabo também dá: baixe `https://SEU-SERVIDOR/apk/AutoClick.apk` no navegador
do celular, instale, abra o app, toque em **Servidor…** e cole o endereço.
Depois toque em **Permitir atualização automática** (é o "instalar apps
desconhecidos").

## 3. Dia a dia

**Publicar um macro novo**
1. No celular: ⋮ do macro → **Exportar** → mande o arquivo pra você (WhatsApp).
2. Salve em `macros/nome-do-macro.json` (o nome do arquivo vira a chave do macro).
3. `git add . && git commit -m "macro X" && git push` → **Deploy** no EasyPanel.
4. Em até 5 min todo celular tem o macro. Apagou o arquivo? Some dos celulares
   também (só os que vieram do servidor; macro gravado no próprio celular fica).

Editar um `.json` que já existe atualiza o macro no lugar. Se ele estiver
rodando, o celular recomeça a passada com a versão nova.

**Mudar configurações**: edite `config.json`, commit, push, deploy.

**Atualizar o app**: mexeu em `app/`, commit, push, deploy. Pronto.

**Ver se deu certo**: abra o painel. Cada celular mostra versão, estado
(rodando/parado/dormindo), se os macros e a config já são os do servidor, e a
situação da atualização.

## 4. `config.json`

```json
{
  "pollSeconds": 120,                 // de quanto em quanto tempo o celular fala com o servidor (mín. 60)
  "selectedMacro": "aquecimento",     // macro que fica na bolha (chave = nome do arquivo em macros/)
  "autorun": {
    "enabled": true,                  // true = todo celular começa este macro; false = não mexe em ninguém
    "macro": "aquecimento",
    "gapMs": 300000,                  // intervalo entre passadas (5 min); 0 = loop direto
    "quiet": true,                    // modo Especial: dorme no horário de silêncio
    "runId": "1"                      // mude (2, 3, ...) pra todo mundo recomeçar do zero
  },
  "quietHours": { "start": 21, "end": 9 },   // horário de silêncio do modo Especial
  "command": { "id": "2026-09-02-a", "action": "relearn" },   // opcional, roda 1x por id
  "devices": {
    "ID-DO-CELULAR": {                // ID: botão "Copiar ID" no app, ou no painel
      "name": "Redmi 1 (Rosilene)",   // nome que aparece no painel
      "chipPhone": "11958193140",     // opcional: força o chip deste celular (ele detecta sozinho lendo a página do AllWin)
      "macros": ["aquecimento"],      // só estes macros vão pra ele (sem esta linha vão todos)
      "autorun": { "enabled": true, "macro": "aquecimento", "gapMs": 600000, "quiet": true, "runId": "1" }
    }
  }
}
```

- `autorun` com `enabled: true` é aplicado **uma vez por combinação de
  valores**: cada celular começa o macro e, se você parar um na bolha, ele fica
  parado até você mudar algo aqui (o `runId`, por exemplo). O repositório vem
  com `enabled: false` de propósito: um deploy nunca começa envios sozinho;
  quem já estava rodando continua. Pra parar todo mundo, use o `command` `stop`.
- **Chip do celular**: a cada passada o app lê, na página do AllWin aberta no Chrome
  (`/gerador/<número>`), qual chip aquele celular opera, e manda no relatório
  (`chipPhone`). É isso que liga o celular ao card do número no AllWin. Aparece no painel
  como "Chip". Se um celular não detectar (página diferente, Chrome sem barra), dá pra
  forçar com `devices[id].chipPhone` no config.json, ou vincular à mão no AllWin.
- `command.action`: `restart` (recomeça), `stop` (para), `relearn` (esquece a
  rota aprendida e recomeça; use quando a rota foi aprendida com a tela errada),
  `home` (tela inicial). Cada `id` roda uma única vez em cada celular.
- Dentro de `devices`, qualquer chave de cima (`autorun`, `pollSeconds`,
  `quietHours`, `command`) pode ser repetida pra valer só naquele celular.

## 5. Painel e API

- `GET /` painel · `GET /api/devices` JSON do painel · `GET /apk/AutoClick.apk` APK
- `POST /api/sync` (o app usa) · `GET /api/manifest?id=...` o que um celular receberia
- Se houver token (`autoclick.syncToken` no `gradle.properties`, ou a env
  `SYNC_TOKEN`), `/api/sync` e `/api/manifest` exigem o cabeçalho `X-Token`. O
  APK é compilado com o mesmo valor, então os celulares mandam sozinhos.

## 6. Rodar tudo aqui no computador (teste)

```bash
scripts/build-apk.sh          # APK em apk/
node server/index.js          # http://localhost:3000
# no celular/emulador, aponte pro seu IP: am start -n com.gm.autoclick/.MainActivity --es server http://192.168.0.10:3000
```

## 7. Problemas comuns

- **Painel diz "precisa de um toque no celular"**: o Android não deixou instalar
  em silêncio. Abra o AutoClick nesse celular e toque em **Instalar
  atualização**. Confira se "instalar apps desconhecidos" está permitido pro
  AutoClick (botão *Permitir atualização automática* no card Servidor, ou
  `adb shell appops set com.gm.autoclick REQUEST_INSTALL_PACKAGES allow`).
- **"serviço DESLIGADO" no painel**: a chave de acessibilidade caiu. Ligue em
  Configurações > Acessibilidade (se estiver cinza, "Permitir configurações
  restritas" em Configurações > Apps > AutoClick > ⋮).
- **Celular "SEM SINAL"**: não fala com o servidor há mais de 2 sincronizações.
  Sem internet, app morto pelo HyperOS (Autostart/bateria) ou celular desligado.
- **Build falhou no EasyPanel**: veja o log do build. Os motivos comuns são
  memória (o Gradle precisa de ~2 GB livres) e o keystore ausente. O app que
  já estava no ar continua rodando; os celulares seguem na versão anterior.
- **Macro não chega**: o arquivo precisa ser o exportado pelo app (começa com
  `{"app":"autoclick",...}`); o log do servidor diz se ignorou algum.
