# Voz e Olhar — aplicativo Android

Assistente para pessoas analfabetas ou com pouca leitura.
Botão verde: tira foto e explica o que é. Botão amarelo: ouve a pergunta.
A resposta aparece na tela e é lida em voz alta.

## Como gerar o APK (sem instalar nada no computador)

1. Crie uma conta grátis em github.com (se ainda não tiver).
2. Clique em **New repository**, dê o nome `voz-e-olhar` e crie.
3. Na página do repositório, clique em **uploading an existing file**.
4. Descompacte o arquivo `VozEOlhar.zip` no computador e **arraste todo o conteúdo da pasta** (inclusive a pasta `.github`) para a página. Clique em **Commit changes**.
   - Se a pasta `.github` não aparecer (ela é "oculta"), crie o arquivo pelo botão
     **Add file → Create new file**, com o nome `.github/workflows/gerar-apk.yml`,
     e cole o conteúdo do arquivo de mesmo nome.
5. Abra a aba **Actions**. A tarefa "Gerar APK" roda sozinha (leva uns 5 minutos).
6. Quando ficar verde, clique nela e baixe **VozEOlhar-apk** no fim da página. Dentro do zip está o `VozEOlhar.apk`.

## Como instalar no celular

1. Mande o `VozEOlhar.apk` para o celular (WhatsApp, Google Drive, cabo USB).
2. Toque no arquivo. O Android vai pedir para **permitir instalar apps desta fonte**: permita.
3. Abra o Voz e Olhar.

## Primeira configuração (feita por quem cuida do aparelho)

Na primeira vez abre a tela de **Ajustes**:

1. Crie uma chave em https://console.anthropic.com (menu API Keys) e coloque crédito.
2. Cole a chave (começa com `sk-ant-`) e toque em **Testar**. Deve aparecer "Funcionou!".
3. Toque em **Fechar**. Pronto.

Por segurança, defina um **limite de gasto mensal** no console da Anthropic.
A chave fica guardada só naquele celular; quem tiver o aparelho consegue usar o crédito.
Os ajustes ficam na rodinha no canto de cima.

## O que o celular precisa

- Android 8 ou mais novo, com internet.
- Aplicativo **Google** instalado (é ele que entende a voz). Em quase todo Android já vem.
- Voz em português instalada (Configurações → Sistema → Idioma → Saída de texto para fala).

## Abrir no Android Studio (opcional)

Abra a pasta do projeto no Android Studio e clique em Run. Ele baixa o resto sozinho.

## Onde fica cada coisa

- `app/src/main/assets/index.html` — a tela e o jeito de falar do assistente (texto `RULES`).
- `app/src/main/java/br/com/vozeolhar/MainActivity.java` — câmera, microfone, voz e conexão com o Claude.
- Modelo usado: `claude-sonnet-5-5` (dá para trocar nos Ajustes).

Observação: este APK é de teste (assinado com chave de desenvolvimento). Serve para instalar em
qualquer celular. Para publicar na Play Store é preciso gerar uma versão assinada e, de preferência,
usar um servidor próprio no lugar da chave guardada no aparelho.
