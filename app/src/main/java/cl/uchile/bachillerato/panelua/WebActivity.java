package cl.uchile.bachillerato.panelua;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collections;

/** Navegador propio de la app: abre cada módulo sin salir a Chrome. La sesión de Claude queda guardada en la app. */
public class WebActivity extends Activity {

    private static final int PRI = Color.parseColor("#A9435F");
    private static final int REQ_FILE = 7;

    /** Captura descargas hechas en la página (enlaces blob:/data: con «download»), en todos los marcos, y las pasa a la app. */
    private static final String HOOK =
            "(function(){if(window.__uaDl)return;window.__uaDl=1;" +
            "function send(href,name){fetch(href).then(function(r){return r.blob()}).then(function(b){var fr=new FileReader();" +
            "fr.onload=function(){var s=String(fr.result);UAApp.save(name||'descarga',b.type||'',s.substring(s.indexOf(',')+1));};fr.readAsDataURL(b);})" +
            ".catch(function(e){UAApp.error(String(e));});}" +
            "function pick(a){if(!a||!a.href)return false;var h=a.href;return h.indexOf('blob:')===0||h.indexOf('data:')===0;}" +
            "document.addEventListener('click',function(ev){var a=ev.target&&ev.target.closest?ev.target.closest('a'):null;" +
            "if(pick(a)){ev.preventDefault();ev.stopPropagation();send(a.href,a.getAttribute('download'));}},true);" +
            "var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(pick(this)){send(this.href,this.getAttribute('download'));return;}return oc.apply(this,arguments);};" +
            "})();";

    private WebView web;
    private ProgressBar bar;
    private TextView title;
    private ValueCallback<Uri[]> fileCb;
    private String target;
    private boolean sawLogin;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        target = url;
        String name = getIntent().getStringExtra("title");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PRI);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = s.top; bottom = s.bottom;
                v.setPadding(s.left, top, s.right, bottom);
            } else {
                v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(), ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            }
            return ins;
        });

        // Barra superior: volver al panel, título y recargar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), 0);
        TextView back = button("←");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        title = new TextView(this);
        title.setText(name != null ? name : "Panel UA");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView reload = button("⟳");
        reload.setOnClickListener(v -> web.loadUrl(target));
        top.addView(reload, new LinearLayout.LayoutParams(dp(48), dp(52)));
        TextView ext = button("⋮");
        ext.setOnClickListener(v -> abrirSistema());
        top.addView(ext, new LinearLayout.LayoutParams(dp(40), dp(52)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setBackgroundColor(Color.WHITE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false); // enlaces «nueva pestaña» se abren aquí mismo
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "UAApp");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, HOOK, Collections.singleton("*"));
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme();
                if (sc.equals("http") || sc.equals("https") || sc.equals("about") || sc.equals("blob") || sc.equals("data")) return false;
                try { // correo, teléfono, etc.
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(WebActivity.this, "No se puede abrir este enlace.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String u, boolean isReload) {
                volverAlModulo(view, u); // también cubre cambios de página internos de claude.ai
            }

            @Override
            public void onPageStarted(WebView view, String u, Bitmap fav) {
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                bar.setVisibility(View.GONE);
                if (volverAlModulo(view, u)) return;
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(HOOK, null);
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                bar.setProgress(p);
                bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        // Descargas por enlace normal (http/https): las baja el sistema, con la sesión de la app
        web.setDownloadListener((u, ua, disp, mime, len) -> {
            if (u.startsWith("blob:") || u.startsWith("data:")) {
                web.evaluateJavascript("(function(){var a=document.createElement('a');a.href=" + jsq(u) + ";a.download=" + jsq(URLUtil.guessFileName(u, disp, mime)) + ";a.click();})()", null);
                return;
            }
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(u));
                String fn = URLUtil.guessFileName(u, disp, mime);
                r.setMimeType(mime);
                r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(u));
                r.addRequestHeader("User-Agent", ua);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Descargando " + fn + "…", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo descargar el archivo.", Toast.LENGTH_LONG).show();
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl(url);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Puente para guardar en Descargas/Panel UA los archivos que genera la página. */
    private class Bridge {
        @JavascriptInterface
        public void save(String name, String mime, String b64) {
            final String fn = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                Uri uri;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fn);
                    if (!mime.isEmpty()) cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Panel UA");
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                } else {
                    File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Panel UA");
                    dir.mkdirs();
                    File f = new File(dir, fn);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
                    uri = null;
                }
                final Uri open = uri;
                final String type = mime;
                runOnUiThread(() -> {
                    Toast.makeText(WebActivity.this, "Guardado en Descargas/Panel UA: " + fn, Toast.LENGTH_LONG).show();
                    if (open != null) {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(open, type.isEmpty() ? "*/*" : type);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try { startActivity(i); } catch (ActivityNotFoundException ignored) { }
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(WebActivity.this, "No se pudo guardar " + fn, Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void error(String msg) {
            runOnUiThread(() -> Toast.makeText(WebActivity.this, "No se pudo descargar el archivo.", Toast.LENGTH_LONG).show());
        }
    }

    /** Tras iniciar sesión, claude.ai lleva al inicio del chat: se vuelve al módulo pedido. */
    private boolean volverAlModulo(WebView view, String u) {
        Uri x = Uri.parse(u);
        String path = x.getPath() == null ? "" : x.getPath();
        if (!"claude.ai".equals(x.getHost())) return false;
        if (path.startsWith("/login") || path.contains("magic") || path.startsWith("/auth") || path.contains("verify") || path.startsWith("/onboarding")) {
            sawLogin = true;
            return false;
        }
        if (sawLogin && !path.startsWith("/artifact")) {
            sawLogin = false;
            view.post(() -> view.loadUrl(target));
            return true;
        }
        return false;
    }

    /** Alternativa si un módulo no carga: abrirlo en la pestaña integrada del navegador del teléfono. */
    private void abrirSistema() {
        String u = web.getUrl() != null && web.getUrl().contains("/artifact/") ? web.getUrl() : target;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
        Bundle b = new Bundle();
        b.putBinder("android.support.customtabs.extra.SESSION", null);
        i.putExtras(b);
        i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", PRI);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay otro navegador en el teléfono.", Toast.LENGTH_LONG).show();
        }
    }

    private static String jsq(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private TextView button(String t) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
