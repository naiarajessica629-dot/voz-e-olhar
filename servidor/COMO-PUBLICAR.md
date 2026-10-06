# Como colocar o servidor do Voz e Olhar no ar

O servidor é o "cérebro" que fica na internet: o app manda a pergunta ou a foto, o servidor
pesquisa em segundo plano e devolve a resposta pronta. As chaves ficam guardadas só nele.
Usa o plano gratuito da Cloudflare (100 mil pedidos por dia).

## 1. Pegar as chaves

**Claude (principal):**
1. Entre em https://platform.claude.com, vá em **Billing** e coloque crédito.
2. Em **Billing**, defina um limite de gasto mensal.
3. Em **API Keys**, clique em **Create Key** e copie o código `sk-ant-...`.
4. Em https://platform.claude.com/settings/capabilities, ligue **Web search**.

**Brave Search (reserva, opcional):**
1. Entre em https://api-dashboard.search.brave.com e crie a conta.
2. Escolha o plano **Free** (2.000 pesquisas por mês).
3. Em **API Keys**, crie uma chave e copie.

## 2. Criar o servidor na Cloudflare

1. Crie uma conta grátis em https://dash.cloudflare.com
2. No menu, abra **Workers & Pages** (em "Compute") e clique em **Create**.
3. Escolha **Start with Hello World**, dê o nome `voz-e-olhar` e clique em **Deploy**.
4. Clique em **Edit code**. Apague tudo o que estiver lá, cole todo o conteúdo do arquivo
   `servidor/worker.js` deste projeto e clique em **Deploy**.

## 3. Guardar as chaves no servidor

1. Volte para a página do worker `voz-e-olhar` e abra **Settings → Variables and Secrets**.
2. Clique em **Add**, escolha o tipo **Secret** e crie estas três:

| Nome                | Valor                                   |
|---------------------|-----------------------------------------|
| `ANTHROPIC_API_KEY` | a chave `sk-ant-...` do Claude          |
| `BRAVE_API_KEY`     | a chave do Brave (pode deixar sem)      |
| `APP_TOKEN`         | `voz--9XFtmCg6UTAzheBpzWreNCF`          |

3. Clique em **Deploy** para salvar.

## 4. Ligar o app ao servidor

1. Na página do worker aparece o endereço, parecido com
   `https://voz-e-olhar.SEU-NOME.workers.dev`. Abra no navegador: deve aparecer
   `{"ok":true,"servico":"Voz e Olhar"}`.
2. Mande esse endereço para o Claude, que coloca ele dentro do APK. Assim ninguém
   precisa configurar nada no celular.
3. Ou, para testar já: no app, toque na rodinha (Ajustes), cole o endereço em
   **Endereço do servidor** e toque em **Testar**.

## Bom saber

- O endereço do servidor não é segredo. As chaves, sim: nunca mande para ninguém.
- Se o crédito do Claude acabar, o servidor usa o Brave. Se os dois falharem, o app
  responde sozinho no modo gratuito (lê a foto no celular e usa a Wikipédia).
- Para mudar o jeito de falar do assistente, edite o texto `REGRAS` no `worker.js`
  na Cloudflare. Não precisa gerar um APK novo.
