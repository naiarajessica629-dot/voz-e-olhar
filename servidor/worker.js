/**
 * Servidor do Voz e Olhar (Cloudflare Worker).
 *
 * O aplicativo manda a pergunta (ou a foto) para cá. O servidor pesquisa na
 * internet em segundo plano e devolve a resposta pronta, em linguagem simples.
 * As chaves ficam guardadas aqui, escondidas — o celular nunca vê nenhuma chave.
 *
 * 1º caminho: Claude + pesquisa na internet (respostas no jeito de conversa).
 * 2º caminho (reserva): Brave Search, quando o Claude falhar ou ficar sem crédito.
 *
 * Variáveis secretas (Settings → Variables and Secrets do Worker):
 *   ANTHROPIC_API_KEY  chave do Claude (sk-ant-...)
 *   BRAVE_API_KEY      chave do Brave Search (opcional, é a reserva)
 *   APP_TOKEN          código combinado com o aplicativo
 */

const MODELO = "claude-sonnet-5-5";

const REGRAS = `Você é o assistente virtual principal do aplicativo "Voz e Olhar", feito para apoiar pessoas analfabetas ou com pouca leitura na rotina do dia a dia.
Seja muito prestativo, paciente, carinhoso e claro.

Como falar:
1. Nunca use termos técnicos, jargões ou palavras difíceis. Fale simples e natural, como numa conversa amiga de compadre ou comadre.
2. Sua resposta vai virar áudio para a pessoa ouvir. Escreva frases curtas, em tom acolhedor e com ritmo calmo.
3. Evite texto longo, listas grandes e números complicados. Se precisar explicar algo, use no máximo 3 passos, falados em frases ("Primeiro... Depois... Por último...").
4. Não use asteriscos, marcadores, emojis, títulos nem formatação. Só texto corrido, de no máximo umas 5 frases curtas.

Quando chegar uma FOTO: diga o que é a coisa de forma simples e explique para que serve ou como ajuda no dia a dia. Exemplo: "Isso é um pacote de arroz. Ele serve para fazer comida. Para cozinhar, você mistura com água e leva ao fogo."
Se for remédio, diga o nome e para que serve, e sempre lembre de perguntar a alguém de confiança, ao farmacêutico ou ao posto de saúde antes de tomar. Se for algo perigoso (veneno, produto de limpeza, fogo, eletricidade), avise com cuidado. Se for dinheiro, conta, documento ou papel, leia o que for importante (valor, data de vencimento, nome) de forma simples. Se a foto estiver ruim, peça com carinho para tirar outra, mais perto e com mais luz.

Quando chegar uma PERGUNTA: responda direto, focando na solução prática, com passo a passo falado e simples.

PESQUISA NA INTERNET: pesquise sempre que a resposta depender de informação atual ou local, como preço, horário, endereço, telefone, data de pagamento de benefício (Bolsa Família, INSS, aposentadoria), notícias, previsão do tempo, vacinação, ônibus, documentos ou "como faço para". Também pesquise para confirmar o nome e o uso de um produto ou remédio quando não tiver certeza.
Depois de pesquisar, conte só o que importa, em palavras simples. Nunca leia endereço de site, link ou código. Se quiser dizer de onde veio a informação, fale simples, por exemplo: "Eu vi no site da Caixa". Se não achar uma informação confiável, diga com sinceridade e indique onde a pessoa pode perguntar (posto de saúde, CRAS, agência da Caixa, farmácia).
Não diga "vou pesquisar" nem explique o que está fazendo: dê só a resposta final.

Sempre com respeito, encorajamento e jeito humano. Responda em português do Brasil.`;

const BUSCA = {
  type: "web_search_20250305",
  name: "web_search",
  max_uses: 3,
  user_location: { type: "approximate", country: "BR", timezone: "America/Sao_Paulo" },
};

export default {
  async fetch(request, env) {
    if (request.method === "GET") return json({ ok: true, servico: "Voz e Olhar" });
    if (request.method !== "POST") return json({ erro: "metodo" }, 405);
    if (!env.APP_TOKEN || request.headers.get("x-app-token") !== env.APP_TOKEN) {
      return json({ erro: "nao_autorizado" }, 401);
    }

    let entrada;
    try { entrada = await request.json(); } catch { return json({ erro: "pedido_invalido" }, 400); }

    const pergunta = String(entrada.pergunta || "").slice(0, 1000).trim();
    const imagem = typeof entrada.imagem === "string" && entrada.imagem.length < 4_000_000 ? entrada.imagem : null;
    const historico = Array.isArray(entrada.historico) ? entrada.historico.slice(-6) : [];
    if (!pergunta && !imagem) return json({ erro: "pedido_invalido" }, 400);

    // 1º caminho: Claude com pesquisa na internet
    if (env.ANTHROPIC_API_KEY) {
      const r = await perguntarClaude(env, pergunta, imagem, historico);
      if (r.texto) return json({ texto: r.texto, fonte: "claude" });
    }

    // 2º caminho: Brave Search (só para perguntas; a foto o celular lê sozinho)
    if (env.BRAVE_API_KEY && pergunta) {
      const t = await perguntarBrave(env, pergunta);
      if (t) return json({ texto: t, fonte: "brave" });
    }

    return json({ erro: "sem_resposta" }, 503);
  },
};

// ------------------------------------------------------------------ Claude

async function perguntarClaude(env, pergunta, imagem, historico) {
  const pedido = imagem
    ? "A pessoa me mandou uma FOTO de um objeto. Olhe a imagem e responda seguindo as regras." +
      (pergunta ? `\nEla também disse: "${pergunta}"` : "")
    : `A pessoa perguntou falando: "${pergunta}"`;
  const conteudo = imagem
    ? [
        { type: "image", source: { type: "base64", media_type: "image/jpeg", data: imagem } },
        { type: "text", text: pedido },
      ]
    : pedido;

  const msgs = [];
  for (const h of historico) {
    if ((h.role === "user" || h.role === "assistant") && typeof h.content === "string" && h.content.trim()) {
      msgs.push({ role: h.role, content: h.content.slice(0, 2000) });
    }
  }
  while (msgs.length && msgs[0].role !== "user") msgs.shift();
  if (msgs.length && msgs[msgs.length - 1].role === "user") msgs.pop();
  msgs.push({ role: "user", content: conteudo });

  const corpo = { model: MODELO, max_tokens: 1024, system: REGRAS, messages: msgs, tools: [BUSCA] };

  for (let volta = 0; volta < 4; volta++) {
    const resp = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify(corpo),
    });
    const txt = await resp.text();

    if (!resp.ok) {
      // Pesquisa desligada no console da Anthropic: tenta de novo sem pesquisar
      if (resp.status === 400 && /web.?search/i.test(txt) && corpo.tools) {
        delete corpo.tools;
        continue;
      }
      return { erro: resp.status };
    }

    let j;
    try { j = JSON.parse(txt); } catch { return { erro: "json" }; }

    // Pesquisa longa: a API pede para continuar de onde parou
    if (j.stop_reason === "pause_turn") {
      corpo.messages.push({ role: "assistant", content: j.content });
      continue;
    }
    return { texto: limpar(textoFinal(j.content || [])) };
  }
  return { erro: "voltas" };
}

/** Pega só o texto depois da última pesquisa (a resposta final). */
function textoFinal(blocos) {
  let ini = 0;
  blocos.forEach((b, i) => {
    if (b.type === "web_search_tool_result" || b.type === "server_tool_use") ini = i + 1;
  });
  const fim = blocos.slice(ini).filter((b) => b.type === "text").map((b) => b.text).join("");
  return fim.trim() ? fim : blocos.filter((b) => b.type === "text").map((b) => b.text).join("");
}

// ------------------------------------------------------------------ Brave (reserva)

async function perguntarBrave(env, pergunta) {
  const base = "https://api.search.brave.com/res/v1/web/search?count=5&safesearch=strict&country=BR&q=" +
    encodeURIComponent(pergunta);
  const headers = { Accept: "application/json", "X-Subscription-Token": env.BRAVE_API_KEY };

  let resp = await fetch(base + "&search_lang=pt-br", { headers });
  if (resp.status === 422) resp = await fetch(base, { headers }); // se o idioma não for aceito
  if (!resp.ok) return null;

  let j;
  try { j = await resp.json(); } catch { return null; }

  const trechos = [];
  const info = j.infobox?.results?.[0];
  if (info?.long_desc) trechos.push(info.long_desc);
  else if (info?.description) trechos.push(info.description);
  for (const f of j.faq?.results || []) {
    if (f.answer) { trechos.push(f.answer); break; }
  }
  for (const r of j.web?.results || []) {
    if (r.description) trechos.push(r.description);
    if (trechos.length >= 3) break;
  }
  if (!trechos.length) return null;

  const frases = [];
  for (const t of trechos) {
    for (const f of semHtml(t).match(/[^.!?]+[.!?]+/g) || [semHtml(t)]) {
      const s = f.trim();
      if (s.length > 15 && !frases.includes(s)) frases.push(s);
      if (frases.length >= 3) break;
    }
    if (frases.length >= 3) break;
  }
  if (!frases.length) return null;
  return limpar("Achei isso na internet. " + frases.join(" "));
}

// ------------------------------------------------------------------ utilidades

function semHtml(t) {
  return String(t)
    .replace(/<[^>]+>/g, "")
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&nbsp;/g, " ");
}

function limpar(t) {
  return String(t || "")
    .replace(/https?:\/\/\S+/g, "")
    .replace(/[*#_>`•]/g, "")
    .replace(/^\s*[-–]\s+/gm, "")
    .replace(/\s+\./g, ".")
    .replace(/[ \t]+/g, " ")
    .trim();
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
