# 🚗💲 Compensa

App Android que lê a oferta de corrida da **Uber Motorista** e da **99 Motorista** e mostra na hora:
🟢 **COMPENSA** ou 🔴 **NÃO COMPENSA**, com voz e vibração.

## O que ele faz

- **Leitura instantânea da tela** (Serviço de Acessibilidade): o verde/vermelho aparece em menos de 0,2 s depois do card.
- **Conta de verdade**: R$/km, R$/hora, km até o passageiro, combustível (preço e consumo do seu carro) e manutenção → **lucro estimado**.
- **IA que aprende**:
  - *IA pessoal*: cada 👍/👎 no balão ensina o app o seu jeito de rodar (horário, dia, chuva, terra, distância…).
  - *IA coletiva*: o servidor junta as avaliações de todos os motoristas e manda o modelo para cada celular.
- **Mapa (OpenStreetMap)**: avisa se o destino é **estrada de terra/cascalho**, e se é **fora da sua cidade** (aí ele soma a volta vazia).
- **Clima (Open-Meteo)**: avisa se está chovendo no destino.
- **Bairros que você evita**: a corrida fica vermelha se o destino tiver um deles.
- **Alertas**: cansaço (10 h+ de turno), horário de pico, preço dinâmico.
- **Extras**: resumo do dia, histórico, calculadora etanol × gasolina, dicas.
- **Login e senha** com painel de administrador: libera o motorista por nome + CPF, ele cria o próprio usuário e senha; bloquear, renovar validade (+30 dias), refazer acesso.

O app **nunca aceita nem recusa corridas sozinho**: ele só mostra. Quem decide é você.

---

## 1) Subir o servidor (no seu servidor)

Precisa de Docker. Para HTTPS, aponte um domínio para o IP do servidor (ex: crie `compensa.duckdns.org` no DuckDNS).

```bash
cd server
# edite docker-compose.yml: troque SEU_DOMINIO e ADMIN_PASS
docker compose up -d
```

Abra `https://SEU_DOMINIO` → entre com `alessandro` e a senha que você colocou.

### Como liberar um motorista
1. No painel, em **Liberar acesso**, coloque o **nome completo** e o **CPF** da pessoa.
2. A pessoa instala o app, toca em **É meu primeiro acesso**, digita nome + CPF e cria o próprio **usuário e senha**.
3. Ela tem **7 dias** para fazer isso. Se vencer, toque em **Renovar convite**.
4. Se ela esquecer a senha: **Refazer 1º acesso** (ela faz de novo com nome + CPF).

O CPF não fica salvo em texto no servidor: só uma "impressão digital" criptográfica e os 2 últimos dígitos para você identificar.

### Jeito mais fácil (script automático)
Dentro da VPS, na pasta `server`:
```bash
nano install.sh      # edite DOMINIO, ADMIN_USER e ADMIN_PASS (as 3 primeiras linhas)
sudo bash install.sh # instala Docker, sobe o servidor e liga o HTTPS sozinho
```

**Sem Docker** (Node 18+): `cd server && ADMIN_USER=alessandro ADMIN_PASS=suasenha PORT=8080 node server.js`
(nesse caso use um proxy com HTTPS, ou no app use `http://IP:8080` — funciona, mas sem criptografia).

Os dados ficam em `server/data/db.json` (faça backup desse arquivo).

## 2) Gerar o APK (sem instalar nada no PC)

1. Crie um repositório no GitHub e envie esta pasta.
2. Vá em **Actions** → "Gerar APK do Compensa" → aguarde ~5 min.
3. Baixe o artefato **Compensa-APK** (zip com o `Compensa.apk`).

(Ou abra a pasta no Android Studio e clique em Run.)

## 3) Instalar no celular

1. Instale o `Compensa.apk` (permita "instalar apps desconhecidos").
2. Abra, coloque o endereço do servidor e entre (ou toque em **É meu primeiro acesso**).
3. Toque em **Ativar leitura da tela** → Compensa → ativar.
   - Android 13+: se estiver cinza, vá em *Configurações › Apps › Compensa › ⋮ › Permitir configurações restritas* e tente de novo.
4. Use **Testar com corrida de exemplo** para ver o balão.
5. Ajuste suas metas (R$/km, R$/hora) e os dados do carro.

## Ajustes finos

- Os pacotes lidos estão em `OfferReaderService.kt` (`com.ubercab.driver`, `com.app99.driver`).
- A leitura do card está em `OfferParser.kt`. Se a Uber/99 mudar o layout, mande prints para ajustar.
- O login vale 30 dias offline; depois o app pede para entrar de novo. Conta bloqueada ou vencida para de funcionar na próxima sincronização.

## Como uma pessoa nova entra

1. Ela instala o app e abre. A **tela de boas-vindas** explica o que o Compensa faz e mostra seu WhatsApp de suporte.
2. Ela toca em **Fazer meu cadastro** e envia **nome + CPF**. Isso fica guardado no servidor como um **pedido de acesso**.
3. No painel, aparece **"📝 Pediu acesso"**. Você toca em **✅ Liberar acesso**.
4. Ela volta ao app, toca em **Já tenho acesso → É meu primeiro acesso**, digita nome + CPF e **cria o login e a senha** dela.
5. **Esqueceu a senha?** No login tem **Esqueci minha senha**: ela envia nome + CPF, aparece **"🔑 Pediu troca de senha"** no painel, e você toca em **Refazer 1º acesso** para ela criar uma nova.

O endereço do servidor já vem preenchido no app (`Config.kt`), então a pessoa nem precisa digitar.

## Suporte e aparelhos

- Dentro do app e na tela de login há um botão **Falar com o suporte (WhatsApp)** que abre conversa com você no número **(11) 93937-0020** (altere em `Driver.kt`, objeto `Support`).
- **1 conta = 1 aparelho** por padrão. Se a pessoa trocar de celular, no painel use **Desvincular** e ela entra no novo.
- Para liberar **2 aparelhos do mesmo motorista**, use **Permitir 2 aparelhos** no painel. Um terceiro é bloqueado.
- Tentar entrar com a conta em outro celular é barrado automaticamente — isso evita emprestar/clonar a conta.
- No app, o motorista escolhe ler **só Uber**, **só 99** ou **os dois**, em "Suas metas e seu carro".

## Painel de administrador no app

Quando você entra com a conta de **admin**, aparece em "Conta" o botão **🛠️ Administração**. Lá, pelo próprio celular, você vê e controla tudo:
- **Números de uso:** motoristas cadastrados, com login criado, instalados no celular, que usaram nas últimas 24h e nos últimos 7 dias, pedidos de acesso e de troca de senha, avaliações recebidas pela IA.
- **Liberar acesso** (nome + CPF) e já definir **quantos dias** a pessoa pode usar.
- Por motorista: **liberar pedido**, bloquear/liberar, **definir dias de acesso**, +30 dias, permitir 1 ou 2 aparelhos, desvincular aparelho, refazer acesso e excluir.
- Treinar a IA coletiva na hora.

Tudo isso também continua disponível no painel web (botão "Abrir painel completo no navegador").

## Localização (GPS)

O app usa a localização atual do motorista para saber se o destino é **fora da cidade onde ele está agora** e contar a volta vazia com precisão — em vez de uma cidade fixa. Como a Uber/99 já mantêm o GPS ligado, o Compensa só lê a última posição conhecida (não gasta bateria à toa).

Na tela do app há o botão **"Permitir localização"**. Para funcionar enquanto dirige (com o app em segundo plano), o ideal é escolher **"Permitir o tempo todo"** quando o Android perguntar. Se o motorista não permitir, o Compensa usa a cidade base das configurações.

## Voz

O app fala em português na hora: **"Corrida compensa"** ou **"Corrida não compensa"**, e o motivo principal (ex.: "valor abaixo da sua meta", "estrada de terra", "passageiro muito longe"). Para a melhor dicção, instale no celular o **Google Fala (Speech Services by Google)** e deixe a voz pt-BR; o app usa a voz do sistema, então quanto melhor o motor de voz, melhor fica.

## Segurança

- Senhas guardadas com **scrypt** (não dá para ler a senha original).
- Login por **token assinado (HMAC)** preso ao aparelho e com validade de 30 dias.
- CPF nunca salvo em texto: só impressão digital (HMAC) + 2 últimos dígitos.
- Proteção contra tentativas repetidas de senha e cabeçalhos de segurança (CSP, HSTS, etc.).
- **Importante:** nenhum APK é impossível de copiar. A proteção real é o servidor: sem um login válido que você liberou, o app não analisa nada.

## Aviso

Uber e 99 não aprovam oficialmente apps de terceiros que leem a tela delas. O Compensa só lê e mostra informações (não clica em nada), o que reduz o risco, mas não o elimina. Use por sua conta.
