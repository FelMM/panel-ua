package cl.uchile.bachillerato.panelua;

import android.app.Activity;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * UA PAB — Panel de Control de la Unidad de Análisis (Programa Académico de Bachillerato, U. de Chile).
 * Pide la misma contraseña que el panel web y abre cada módulo en Chrome, con la sesión de Claude del teléfono.
 */
public class MainActivity extends Activity {

    // SHA-256 de la contraseña del panel web (la contraseña no se guarda en la app).
    private static final String HASH = "ca29e3c933cd426e5014d897541a10d080c47e086f6138cddc40c4c595eecd6d";
    private static final String PANEL = "https://claude.ai/artifact/BrLCtw2LcvFoEqp9xxwQpX";
    private static final long RELOCK_MS = 10 * 60 * 1000L; // se vuelve a pedir la clave tras 10 min en segundo plano

    private static final int BG = Color.parseColor("#F7F6F9"), CARD = Color.WHITE, INK = Color.parseColor("#2D2E2E"),
            MUT = Color.parseColor("#635F69"), LINE = Color.parseColor("#E7E3EC"), PRI = Color.parseColor("#A9435F"),
            PRI2 = Color.parseColor("#F5EEF4"), TEAL = Color.parseColor("#1A8FA5"), TEAL2 = Color.parseColor("#E6F5F8"),
            PLUM = Color.parseColor("#6B4660"), PLUM2 = Color.parseColor("#F1EBF0"), CORAL = Color.parseColor("#EF5866"),
            GRAY = Color.parseColor("#8A8590");

    /** Módulos: id (el mismo del panel web), sección, rótulo, título, descripción, sigla del ícono, url (null = próximamente). */
    private static final String[][] MODS = {
            {"pab", "Seguimiento de estudiantes", "Módulo 1", "Programa Académico de Bachillerato (PAB)", "Plataforma de seguimiento de estudiantes del PAB.", "PAB", "https://claude.ai/artifact/PSe3HVvBY7xTNHBym6VB9X"},
            {"uch", "Seguimiento de estudiantes", "Módulo 2", "Universidad de Chile (UChile)", "Seguimiento y comparación de estudiantes PAB con sus pares de carrera.", "UCh", "https://claude.ai/artifact/3QywS8JZiiEmsqgsy3RCdS"},
            {"sat", "Seguimiento de estudiantes", "Módulo 3", "Sistema de Alerta Temprana (SAT)", "Identificación oportuna de estudiantes en riesgo académico.", "SAT", null},
            {"enc1", "Encuestas aplicadas", "Encuesta 1", "Encuesta Docente de Medio Semestre (EMS)", "Resultados de evaluación Docente de Medio Semestre.", "EMS", "https://claude.ai/artifact/EkmzeB95xRwn9QYqUFc59g"},
            {"enc2", "Encuestas aplicadas", "Encuesta 2", "Encuesta de Abandono Estudiantil", "Análisis de causa y perfiles de abandono en el PAB.", "AB", "https://claude.ai/artifact/RzKbTFjz2VfkA9piMZXhiK"},
            {"enc3", "Encuestas aplicadas", "Encuesta 3", "Encuesta de Carga Académica", "Percepción de los estudiantes y docentes sobre la carga académica de las asignaturas.", "CA", "https://claude.ai/artifact/6XgHh4ajC7PibXdPk8KcBF"},
            {"notas", "Informes de Notas", "Módulo", "Informes de Notas", "Reportes de calificaciones y rendimiento académico de los estudiantes.", "N", "https://claude.ai/artifact/HNPAVKqummi8N1wCZKxw2m"},
    };

    /** Configuración publicada en el repositorio (modulos.json): estado de cada módulo (igual que los interruptores del
     *  panel web) y accesos por persona (huella SHA-256 del código personal → módulos). Se actualiza al abrir la app. */
    private static final String CONFIG = "https://raw.githubusercontent.com/FelMM/panel-ua/main/modulos.json";
    private static final String DEF = "{\"estado\":{\"pab\":true,\"uch\":false,\"sat\":false,\"enc1\":true,\"enc2\":true,\"enc3\":true,\"notas\":true},\"accesos\":{}}";
    private JSONObject config;

    private boolean unlocked;
    /** null = creador (todos los módulos); si no, módulos que el creador le asignó a la persona. */
    private String[] permitidos;
    private String quien = "";
    private long stoppedAt;
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        unlocked = state != null && state.getBoolean("unlocked", false);
        if (state != null) { permitidos = state.getStringArray("permitidos"); quien = state.getString("quien", ""); }
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = s.top; bottom = s.bottom; left = s.left; right = s.right;
            } else {
                top = ins.getSystemWindowInsetTop(); bottom = ins.getSystemWindowInsetBottom();
                left = ins.getSystemWindowInsetLeft(); right = ins.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return ins;
        });
        setContentView(root);
        config = parse(getSharedPreferences("ua", MODE_PRIVATE).getString("config", DEF));
        render();
        refreshEstado();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("unlocked", unlocked);
        out.putStringArray("permitidos", permitidos);
        out.putString("quien", quien);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (unlocked && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_MS) {
            unlocked = false;
            render();
        }
    }

    private static JSONObject parse(String s) {
        try { return new JSONObject(s); } catch (Exception e) { try { return new JSONObject(DEF); } catch (Exception e2) { return new JSONObject(); } }
    }

    private boolean activo(String id) {
        JSONObject e = config.optJSONObject("estado");
        return e != null && e.optBoolean(id, false);
    }

    private boolean permitido(String id) {
        if (permitidos == null) return true;
        for (String x : permitidos) if (x.equals(id)) return true;
        return false;
    }

    /** Contraseña del panel = creador (todo). Código personal = solo los módulos que el creador le dio. */
    private boolean ingresar(String txt) {
        if (HASH.equals(sha256(txt.trim()))) { permitidos = null; quien = "Creador"; return true; }
        String code = txt.replaceAll("[\\s-]", "").toUpperCase();
        JSONObject acc = config.optJSONObject("accesos");
        JSONObject a = acc == null || code.isEmpty() ? null : acc.optJSONObject(sha256("UAPAB:" + code));
        if (a == null) return false;
        JSONArray m = a.optJSONArray("m");
        permitidos = new String[m == null ? 0 : m.length()];
        for (int i = 0; i < permitidos.length; i++) permitidos[i] = m.optString(i);
        quien = a.optString("n", "");
        return true;
    }

    /** Descarga el estado vigente de los módulos (sin bloquear la pantalla); si cambió, redibuja. */
    private void refreshEstado() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(CONFIG + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setUseCaches(false);
                if (c.getResponseCode() != 200) return;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                }
                JSONObject j = new JSONObject(sb.toString());
                if (!j.has("estado") || j.toString().equals(config.toString())) return;
                getSharedPreferences("ua", MODE_PRIVATE).edit().putString("config", j.toString()).apply();
                runOnUiThread(() -> { config = j; if (unlocked) render(); });
            } catch (Exception ignored) {
                // sin conexión: se usa el último estado guardado
            }
        }).start();
    }

    private void render() {
        root.removeAllViews();
        root.addView(unlocked ? home() : lock(), new FrameLayout.LayoutParams(-1, -1));
        root.requestApplyInsets();
    }

    // ---------- Pantalla de contraseña ----------
    private View lock() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(20), dp(20), dp(20), dp(20));
        sv.addView(wrap, new ViewGroup.LayoutParams(-1, -1));

        LinearLayout card = column();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(24));
        card.setBackground(round(CARD, 18, LINE));
        card.setElevation(dp(2));
        wrap.addView(card, new LinearLayout.LayoutParams(Math.min(dp(380), getResources().getDisplayMetrics().widthPixels - dp(40)), -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        card.addView(logo, new LinearLayout.LayoutParams(dp(96), -2));

        TextView t = text("UA PAB", 22, INK, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t, margins(-1, -2, 0, 14, 0, 2));
        TextView s = text("Unidad de Análisis · Programa Académico de Bachillerato", 13, MUT, false);
        s.setGravity(Gravity.CENTER);
        card.addView(s, margins(-1, -2, 0, 0, 0, 18));

        EditText pw = new EditText(this);
        pw.setHint("Contraseña o código de acceso");
        pw.setSingleLine(true);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setImeOptions(EditorInfo.IME_ACTION_GO);
        pw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        pw.setTextColor(INK);
        pw.setHintTextColor(GRAY);
        pw.setBackground(round(BG, 10, LINE));
        pw.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(pw, new LinearLayout.LayoutParams(-1, -2));

        TextView err = text("", 13, CORAL, false);
        err.setGravity(Gravity.CENTER);
        card.addView(err, margins(-1, -2, 0, 8, 0, 0));

        Button go = new Button(this);
        go.setText("Ingresar");
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setBackground(round(PRI, 10, 0));
        go.setStateListAnimator(null);
        card.addView(go, margins(-1, dp(48), 0, 10, 0, 0));

        Runnable check = () -> {
            if (ingresar(pw.getText().toString())) {
                hideKeyboard(pw);
                unlocked = true;
                render();
            } else {
                err.setText("Contraseña o código incorrecto. Si te dieron un código hace poco, revisa tu conexión y vuelve a intentar.");
                refreshEstado();
                pw.selectAll();
            }
        };
        go.setOnClickListener(v -> check.run());
        pw.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                check.run();
                return true;
            }
            return false;
        });
        pw.requestFocus();
        return sv;
    }

    // ---------- Inicio con los módulos ----------
    private View home() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = column();
        col.setPadding(dp(16), dp(14), dp(16), dp(24));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));

        // Encabezado
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        head.addView(logo, new LinearLayout.LayoutParams(dp(52), -2));
        LinearLayout ht = column();
        ht.setPadding(dp(12), 0, 0, 0);
        ht.addView(text("UA PAB", 22, INK, true));
        ht.addView(text(permitidos == null ? "Unidad de Análisis · Panel de Control" : (quien.isEmpty() ? "Tus módulos" : "Hola, " + quien), 13, MUT, false));
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        TextView salir = text("Bloquear", 13, PRI, true);
        salir.setPadding(dp(10), dp(8), dp(4), dp(8));
        salir.setOnClickListener(v -> { unlocked = false; permitidos = null; quien = ""; render(); });
        head.addView(salir);
        col.addView(head);

        // Acceso al panel web completo
        LinearLayout web = new LinearLayout(this);
        web.setGravity(Gravity.CENTER_VERTICAL);
        web.setPadding(dp(14), dp(12), dp(14), dp(12));
        web.setBackground(round(PRI2, 12, 0));
        TextView wt = text("Abrir el panel web completo", 14, PRI, true);
        web.addView(wt, new LinearLayout.LayoutParams(0, -2, 1));
        web.addView(text("→", 16, PRI, true));
        web.setOnClickListener(v -> open(PANEL, "Panel de Control"));
        if (permitidos == null) col.addView(web, margins(-1, -2, 0, 16, 0, 4));

        String sec = null;
        int vis = 0;
        for (String[] m : MODS) {
            if (!permitido(m[0])) continue;
            vis++;
            if (!m[1].equals(sec)) {
                sec = m[1];
                int cnt = 0;
                for (String[] x : MODS) if (x[1].equals(sec) && permitido(x[0])) cnt++;
                col.addView(sectionHeader(sec, cnt, accent(sec)), margins(-1, -2, 0, 22, 0, 8));
            }
            col.addView(tile(m), margins(-1, -2, 0, 0, 0, 10));
        }

        if (vis == 0) col.addView(text("Todavía no tienes módulos asignados. Pídele acceso al creador de la app.", 14, MUT, false), margins(-1, -2, 0, 22, 0, 0));
        if (permitidos == null) col.addView(text("Eres el creador: ves todos los módulos. Cada persona entra con su código de acceso y solo ve los módulos que le asignaste.", 12, MUT, false), margins(-1, -2, 0, 14, 0, 0));
        TextView note = text("Los módulos se abren en una pestaña integrada, dentro de la app, con la sesión de Claude del navegador del teléfono. "
                + "La primera vez puede pedir iniciar sesión en Claude. Para volver al panel, toca × o Atrás.", 12, MUT, false);
        col.addView(note, margins(-1, -2, 0, 14, 0, 0));
        TextView foot = text("Creado por la Unidad de Análisis de Bachillerato\nUniversidad de Chile", 12, GRAY, false);
        foot.setGravity(Gravity.CENTER);
        col.addView(foot, margins(-1, -2, 0, 22, 0, 0));
        return sv;
    }

    private View sectionHeader(String title, int count, int color) {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackground(round(color, 2, 0));
        h.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(20)));
        TextView t = text(title, 16, INK, true);
        t.setPadding(dp(10), 0, dp(6), 0);
        t.setSingleLine(true);
        h.addView(t);
        TextView c = text("· " + count, 13, GRAY, false);
        c.setSingleLine(true);
        h.addView(c);
        return h;
    }

    private View tile(String[] m) {
        final boolean on = m[6] != null && activo(m[0]);
        final String url = on ? m[6] : null;
        final String estadoTxt = m[6] == null ? "Próximamente" : "No disponible";
        int ac = accent(m[1]), ac2 = accent2(m[1]);
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round(CARD, 14, LINE));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = text(m[5], m[5].length() > 2 ? 13 : 15, url == null ? GRAY : ac, true);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(round(url == null ? BG : ac2, 12, 0));
        top.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tt = column();
        tt.setPadding(dp(12), 0, 0, 0);
        TextView num = text(m[2].toUpperCase(), 11, GRAY, true);
        num.setLetterSpacing(0.06f);
        tt.addView(num);
        tt.addView(text(m[3], 16, INK, true));
        top.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);

        TextView d = text(m[4], 13, MUT, false);
        card.addView(d, margins(-1, -2, 0, 10, 0, 10));

        TextView go = text(url == null ? estadoTxt : "Entrar →", 14, url == null ? GRAY : ac, true);
        card.addView(go);
        if (url != null) {
            card.setClickable(true);
            card.setOnClickListener(v -> open(url, m[3]));
        } else {
            card.setAlpha(0.75f);
            card.setOnClickListener(v -> Toast.makeText(this, m[3] + ": " + (m[6] == null ? "módulo en preparación." : "no disponible por ahora."), Toast.LENGTH_SHORT).show());
        }
        return card;
    }

    private int accent(String sec) {
        return sec.startsWith("Seguimiento") ? PRI : sec.startsWith("Encuestas") ? TEAL : PLUM;
    }

    private int accent2(String sec) {
        return sec.startsWith("Seguimiento") ? PRI2 : sec.startsWith("Encuestas") ? TEAL2 : PLUM2;
    }

    // ---------- Utilidades ----------
    /** Abre el módulo en la pestaña integrada (se muestra dentro de la app, con el motor y la sesión del navegador del
     *  teléfono). claude.ai no muestra los módulos en el navegador interno de las apps, por eso este es el camino principal. */
    private void open(String url, String title) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        Bundle b = new Bundle();
        b.putBinder("android.support.customtabs.extra.SESSION", null);
        i.putExtras(b);
        i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", PRI);
        i.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
        i.putExtra("androidx.browser.customtabs.extra.SHARE_STATE", 2);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Intent w = new Intent(this, WebActivity.class); // sin navegador en el teléfono: navegador interno
            w.putExtra("url", url);
            w.putExtra("title", title);
            startActivity(w);
        }
    }

    private static String sha256(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private GradientDrawable round(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
