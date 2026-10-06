package br.com.vozeolhar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voz e Olhar — tela única com WebView (interface em assets/index.html).
 * A parte nativa cuida de: câmera, reconhecimento de fala, leitura em voz alta
 * e chamada à API do Claude. A página conversa com ela pelo objeto JS "Android".
 */
public class MainActivity extends Activity {

    private static final int REQ_FOTO = 10;
    private static final int REQ_MIC = 11;
    private static final String PREFS = "voz_e_olhar";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsPronto = false;
    private SpeechRecognizer ouvinte;
    private File arquivoFoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Ponte(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("pt", "BR"));
                ttsPronto = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsPronto) ttsPronto = tts.setLanguage(new Locale("pt")) >= 0;
                tts.setSpeechRate(0.9f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { js("onFalaComecou", ""); }
                    @Override public void onDone(String id) { js("onFalaAcabou", ""); }
                    @Override public void onError(String id) { js("onFalaAcabou", ""); }
                });
            }
        });
    }

    /** Chama window.<funcao>(texto) na página, sempre na thread da tela. */
    private void js(String funcao, String texto) {
        final String code = "window." + funcao + " && window." + funcao + "(" + JSONObject.quote(texto) + ")";
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    // ---------------------------------------------------------------- Ponte JS

    private class Ponte {

        @JavascriptInterface
        public String getKey() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("key", "");
        }

        @JavascriptInterface
        public void setKey(String key) {
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putString("key", key == null ? "" : key.trim());
            e.apply();
        }

        @JavascriptInterface
        public void falar(String texto) {
            runOnUiThread(() -> {
                if (tts == null || !ttsPronto) { js("onFalaAcabou", ""); return; }
                tts.stop();
                tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "fala");
            });
        }

        @JavascriptInterface
        public void pararFala() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); js("onFalaAcabou", ""); });
        }

        @JavascriptInterface
        public void tirarFoto() {
            runOnUiThread(MainActivity.this::abrirCamera);
        }

        @JavascriptInterface
        public void ouvir() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                } else {
                    comecarAOuvir();
                }
            });
        }

        @JavascriptInterface
        public void pararDeOuvir() {
            runOnUiThread(() -> { if (ouvinte != null) ouvinte.stopListening(); });
        }

        /** Recebe o corpo JSON pronto e devolve a resposta crua em onResposta / onErro. */
        @JavascriptInterface
        public void perguntar(String corpoJson) {
            new Thread(() -> chamarClaude(corpoJson)).start();
        }

        /** Manda a pergunta/foto para o servidor do Voz e Olhar. Resposta em onServidor / onServidorErro. */
        @JavascriptInterface
        public void perguntarServidor(String url, String token, String corpoJson) {
            new Thread(() -> chamarServidor(url, token, corpoJson)).start();
        }

        /** Busca grátis na Wikipédia em português. Resposta em onWeb(json com id e corpo). */
        @JavascriptInterface
        public void buscarWeb(String id, String url) {
            new Thread(() -> baixarWeb(id, url)).start();
        }

        /** Abre a última foto no Google Lens (ou em outro app que saiba pesquisar imagens). */
        @JavascriptInterface
        public void abrirLens() {
            runOnUiThread(MainActivity.this::mandarFotoProGoogle);
        }

        /** Abre o Google Assistente para a pessoa perguntar falando; se não houver, pesquisa no Google. */
        @JavascriptInterface
        public void perguntarGoogle(String pergunta) {
            runOnUiThread(() -> abrirGoogle(pergunta));
        }
    }

    // ---------------------------------------------------------------- Google e internet

    private void mandarFotoProGoogle() {
        if (arquivoFoto == null || !arquivoFoto.exists()) { js("onErro", "sem_foto"); return; }
        Uri uri = FileProvider.getUriForFile(this, "br.com.vozeolhar.fileprovider", arquivoFoto);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            Intent g = new Intent(i);
            g.setPackage("com.google.android.googlequicksearchbox");
            startActivity(g);
        } catch (Exception e) {
            try { startActivity(Intent.createChooser(i, "Pesquisar a foto")); }
            catch (Exception e2) { js("onErro", "sem_google"); }
        }
    }

    private void abrirGoogle(String pergunta) {
        if (tts != null) tts.stop();
        try {
            Intent v = new Intent("android.intent.action.VOICE_COMMAND");
            v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(v);
            return;
        } catch (Exception ignored) { }
        try {
            Intent w = new Intent(Intent.ACTION_WEB_SEARCH);
            w.putExtra(android.app.SearchManager.QUERY, pergunta == null ? "" : pergunta);
            startActivity(w);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(pergunta == null ? "" : pergunta))));
            } catch (Exception e2) { js("onErro", "sem_google"); }
        }
    }

    private void chamarServidor(String url, String token, String corpoJson) {
        HttpURLConnection c = null;
        try {
            if (url == null || !url.startsWith("https://")) { js("onServidorErro", "config"); return; }
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-app-token", token == null ? "" : token);
            try (OutputStream os = c.getOutputStream()) {
                os.write(corpoJson.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String corpo = ler(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 200) js("onServidor", corpo);
            else if (code == 401) js("onServidorErro", "token");
            else js("onServidorErro", "servidor");
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException e) {
            js("onServidorErro", "sem_internet");
        } catch (Exception e) {
            js("onServidorErro", "servidor");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void baixarWeb(String id, String url) {
        JSONObject r = new JSONObject();
        HttpURLConnection c = null;
        try {
            r.put("id", id);
            if (!url.startsWith("https://pt.wikipedia.org/")) throw new Exception("site não permitido");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "VozEOlhar/1.2 (aplicativo Android de acessibilidade)");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            r.put("status", code);
            r.put("corpo", ler(code >= 400 ? c.getErrorStream() : c.getInputStream()));
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException e) {
            try { r.put("status", -1); } catch (Exception ignored) { }
        } catch (Exception e) {
            try { r.put("status", 0); } catch (Exception ignored) { }
        } finally {
            if (c != null) c.disconnect();
        }
        js("onWeb", r.toString());
    }

    // ---------------------------------------------------------------- Leitura da foto no celular

    /** Lê o texto da foto e diz que tipo de coisa parece ser. Resultado em onAnalise(json). */
    private void analisarFoto(Bitmap b) {
        final InputImage img = InputImage.fromBitmap(b, 0);
        final JSONObject res = new JSONObject();
        final JSONArray blocos = new JSONArray();
        final JSONArray rotulos = new JSONArray();
        final int[] faltam = {2};
        final Runnable fim = () -> {
            synchronized (res) {
                faltam[0]--;
                if (faltam[0] > 0) return;
            }
            try { res.put("blocos", blocos); res.put("rotulos", rotulos); } catch (Exception ignored) { }
            js("onAnalise", res.toString());
        };

        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
                .addOnSuccessListener(texto -> {
                    for (Text.TextBlock bl : texto.getTextBlocks()) {
                        for (Text.Line ln : bl.getLines()) {
                            try {
                                JSONObject o = new JSONObject();
                                o.put("t", ln.getText());
                                o.put("h", ln.getBoundingBox() != null ? ln.getBoundingBox().height() : 0);
                                o.put("y", ln.getBoundingBox() != null ? ln.getBoundingBox().top : 0);
                                blocos.put(o);
                            } catch (Exception ignored) { }
                        }
                    }
                    fim.run();
                })
                .addOnFailureListener(e -> fim.run());

        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS).process(img)
                .addOnSuccessListener(lista -> {
                    for (ImageLabel l : lista) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("t", l.getText());
                            o.put("c", l.getConfidence());
                            rotulos.put(o);
                        } catch (Exception ignored) { }
                    }
                    fim.run();
                })
                .addOnFailureListener(e -> fim.run());
    }

    // ---------------------------------------------------------------- Câmera

    private void abrirCamera() {
        try {
            File pasta = new File(getCacheDir(), "fotos");
            if (!pasta.exists()) pasta.mkdirs();
            arquivoFoto = new File(pasta, "foto.jpg");
            if (arquivoFoto.exists()) arquivoFoto.delete();
            Uri uri = FileProvider.getUriForFile(this, "br.com.vozeolhar.fileprovider", arquivoFoto);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_FOTO);
        } catch (Exception e) {
            js("onErro", "camera");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FOTO) return;
        if (resultCode != RESULT_OK || arquivoFoto == null || !arquivoFoto.exists()) {
            js("onFotoCancelada", "");
            return;
        }
        new Thread(() -> {
            try {
                Bitmap foto = prepararFoto(arquivoFoto);
                ByteArrayOutputStream saida = new ByteArrayOutputStream();
                foto.compress(Bitmap.CompressFormat.JPEG, 85, saida);
                js("onFoto", Base64.encodeToString(saida.toByteArray(), Base64.NO_WRAP));
                analisarFoto(foto);
            } catch (Exception e) {
                js("onErro", "camera");
            }
        }).start();
    }

    /** Diminui a foto para no máximo 1280 px e corrige o giro. */
    private Bitmap prepararFoto(File f) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int maior = Math.max(o.outWidth, o.outHeight);
        int amostra = 1;
        while (maior / (amostra * 2) >= 1280) amostra *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = amostra;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (b == null) throw new Exception("foto vazia");

        float escala = 1280f / Math.max(b.getWidth(), b.getHeight());
        Matrix m = new Matrix();
        if (escala < 1f) m.postScale(escala, escala);
        int giro = new ExifInterface(f.getAbsolutePath())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (giro == ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90);
        else if (giro == ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180);
        else if (giro == ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    // ---------------------------------------------------------------- Microfone

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) comecarAOuvir();
        else js("onErro", "mic_negado");
    }

    private void comecarAOuvir() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("onErro", "sem_reconhecimento");
            return;
        }
        if (tts != null) tts.stop();
        if (ouvinte != null) ouvinte.destroy();
        ouvinte = SpeechRecognizer.createSpeechRecognizer(this);
        ouvinte.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { js("onOuvindo", ""); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { js("onParouDeOuvir", ""); }
            @Override public void onEvent(int t, Bundle p) { }

            @Override public void onPartialResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) js("onParcial", l.get(0));
            }

            @Override public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                js("onOuviu", (l != null && !l.isEmpty()) ? l.get(0) : "");
            }

            @Override public void onError(int erro) {
                if (erro == SpeechRecognizer.ERROR_NO_MATCH || erro == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) js("onOuviu", "");
                else if (erro == SpeechRecognizer.ERROR_NETWORK || erro == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) js("onErro", "sem_internet");
                else if (erro == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) js("onErro", "mic_negado");
                else js("onErro", "mic");
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        ouvinte.startListening(i);
    }

    // ---------------------------------------------------------------- Claude

    private void chamarClaude(String corpoJson) {
        String key = getSharedPreferences(PREFS, MODE_PRIVATE).getString("key", "");
        if (key.isEmpty()) { js("onErro", "sem_chave"); return; }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API_URL).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            try (OutputStream os = c.getOutputStream()) {
                os.write(corpoJson.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String corpo = ler(in);
            if (code == 200) js("onResposta", corpo);
            else if (code == 401 || code == 403) js("onErro", "chave_invalida");
            else if (code == 429 || code == 529) js("onErro", "ocupado");
            else if (code == 400 && corpo.contains("credit")) js("onErro", "sem_credito");
            else if (code == 400 && corpo.toLowerCase(Locale.ROOT).replace('_', ' ').contains("web search")) js("onErro", "sem_pesquisa");
            else js("onErro", "servidor");
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException e) {
            js("onErro", "sem_internet");
        } catch (Exception e) {
            js("onErro", "servidor");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String ler(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    // ---------------------------------------------------------------- Ciclo de vida

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.voltar && window.voltar()", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ouvinte != null) ouvinte.cancel();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (ouvinte != null) ouvinte.destroy();
        super.onDestroy();
    }
}
