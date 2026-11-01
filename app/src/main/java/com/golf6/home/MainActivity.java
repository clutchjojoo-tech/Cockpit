package com.golf6.home;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.app.ActivityOptions;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ComponentInfo;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import org.xmlpull.v1.XmlPullParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    // Prénom prononcé dans le message d'accueil.
    private static final String PRENOM = "Jonathan";
    // Délai (ms) avant la phrase, pour la caler sur l'animation de démarrage.
    private static final long DELAI_VOIX = 1400;

    // Une seule annonce par démarrage de l'autoradio (tant que le processus vit).
    private static boolean dejaSalue = false;

    // L'animation de démarrage ne se joue qu'une fois par démarrage du poste :
    // si Android recrée l'écran (retour de ZLink), on retrouve la dernière page.
    private static boolean demarrageJoue = false;

    private WebView web;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean ttsPret = false;
    private boolean salutationEnAttente = false;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("accueil", MODE_PRIVATE);
        initVoix();

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                sendLocation();
                sendZlink();
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html?night=" + (isSystemNight() ? "1" : "0"));

        appliquerLuminosite(prefs.getInt("bright", 80));
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        sendLocation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pleinEcran();
        sendLocation();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            pleinEcran();
        }
    }

    // Cache les barres du système pour utiliser tout l'écran.
    @SuppressWarnings("deprecation")
    private void pleinEcran() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Exception e) {
            // On garde les barres du système.
        }
    }

    // Essai : lance ZLink dans la moitié droite de l'écran (nécessite le mode fenêtres du système).
    private void essaiFenetreZlink() {
        String pkg = prefs.getString("app_carplay", null);
        if (pkg == null) pkg = zlinkPackage();
        if (pkg == null) {
            Toast.makeText(this, "ZLink introuvable", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            ActivityOptions ao = ActivityOptions.makeBasic();
            ao.setLaunchBounds(new Rect(w / 3, 0, w, h));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            startActivity(i, ao.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, "Fenêtre non prise en charge par ce poste", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Voix : message de bienvenue ----------

    private void initVoix() {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) {
                    return;
                }
                try {
                    int r = tts.setLanguage(Locale.FRANCE);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        return;
                    }
                    choisirVoixFeminine();
                    tts.setSpeechRate(0.95f);
                    ttsPret = true;
                    if (salutationEnAttente) {
                        salutationEnAttente = false;
                        saluer();
                    }
                } catch (Exception e) {
                    // Pas de voix : on reste silencieux.
                }
            }
        });

        if (!dejaSalue) {
            dejaSalue = true;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!prefs.getBoolean("voice_on", true)) {
                        return;
                    }
                    if (ttsPret) {
                        saluer();
                    } else {
                        salutationEnAttente = true;
                    }
                }
            }, DELAI_VOIX);
        }
    }

    private String phraseSalutation() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String mot;
        if (h >= 5 && h < 12) {
            mot = "Bonjour";
        } else if (h >= 12 && h < 18) {
            mot = "Bon après-midi";
        } else {
            mot = "Bonsoir";
        }
        return mot + " " + PRENOM;
    }

    private void saluer() {
        try {
            Bundle params = new Bundle();
            float vol = prefs.getInt("voice_vol", 90) / 100f;
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol);
            tts.speak(phraseSalutation(), TextToSpeech.QUEUE_FLUSH, params, "salut");
        } catch (Exception e) {
            // On ignore : la voix est facultative.
        }
    }

    private void choisirVoixFeminine() {
        if (Build.VERSION.SDK_INT < 21) {
            return;
        }
        try {
            Voice choix = null;
            for (Voice v : tts.getVoices()) {
                Locale l = v.getLocale();
                if (l == null || !"fr".equals(l.getLanguage())) continue;
                if (v.isNetworkConnectionRequired()) continue;
                String n = v.getName().toLowerCase(Locale.ROOT);
                if (n.contains("female") || n.contains("femme")) {
                    choix = v;
                    break;
                }
                if (choix == null && "FR".equals(l.getCountry())) {
                    choix = v;
                }
            }
            if (choix != null) {
                tts.setVoice(choix);
            }
        } catch (Exception e) {
            // On garde la voix par défaut.
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    // ---------- Mode nuit du système ----------

    private boolean isSystemNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final String js = "window.setNight && window.setNight(" + isSystemNight() + ")";
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Écran d'accueil : le bouton retour ne fait rien.
    }

    // ---------- Localisation (météo) ----------

    private void sendLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            if (best != null) {
                final String js = String.format(Locale.US,
                        "window.setLocation && window.setLocation(%f,%f)",
                        best.getLatitude(), best.getLongitude());
                web.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(js, null);
                    }
                });
            }
        } catch (SecurityException e) {
            // Pas de permission de localisation : la météo reste masquée.
        } catch (Exception e) {
            // On ignore : la météo est facultative.
        }
    }

    // ---------- Applications ----------

    private List<ResolveInfo> launchables() {
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(main, 0);
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        for (ResolveInfo ri : list) {
            if (!getPackageName().equals(ri.activityInfo.packageName)) {
                out.add(ri);
            }
        }
        return out;
    }

    private String findByKeywords(String[] kws) {
        List<ResolveInfo> all = launchables();
        PackageManager pm = getPackageManager();
        for (String kw : kws) {
            String k = kw.trim().toLowerCase(Locale.ROOT);
            if (k.length() == 0) continue;
            for (ResolveInfo ri : all) {
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
                if (label.contains(k) || pkg.contains(k)) {
                    return ri.activityInfo.packageName;
                }
            }
        }
        return null;
    }

    private void launchApp(String id, String[] kws) {
        String pkg = prefs.getString("app_" + id, null);
        if (pkg == null) {
            pkg = findByKeywords(kws);
        }
        if (pkg == null) {
            pick(id);
            return;
        }
        launchPackage(pkg, id);
    }

    private void launchPackage(String pkg, String idPourChoix) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            if (idPourChoix != null) {
                pick(idPourChoix);
            }
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir cette application", Toast.LENGTH_SHORT).show();
        }
    }

    private void pick(final String id) {
        final PackageManager pm = getPackageManager();
        final List<ResolveInfo> all = launchables();
        Collections.sort(all, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
            }
        });
        final String[] labels = new String[all.size()];
        final String[] pkgs = new String[all.size()];
        for (int n = 0; n < all.size(); n++) {
            labels[n] = String.valueOf(all.get(n).loadLabel(pm));
            pkgs[n] = all.get(n).activityInfo.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choisis l'application à ouvrir")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        prefs.edit().putString("app_" + id, pkgs[which]).apply();
                        Intent i = pm.getLaunchIntentForPackage(pkgs[which]);
                        if (i != null) startActivity(i);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private String iconeEnBase64(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(c);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            b.recycle();
            return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private void envoyerApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PackageManager pm = getPackageManager();
                    List<ResolveInfo> list = launchables();
                    Collections.sort(list, new Comparator<ResolveInfo>() {
                        @Override
                        public int compare(ResolveInfo a, ResolveInfo b) {
                            PackageManager p = getPackageManager();
                            return String.valueOf(a.loadLabel(p)).compareToIgnoreCase(String.valueOf(b.loadLabel(p)));
                        }
                    });
                    Set<String> vus = new HashSet<String>();
                    JSONArray arr = new JSONArray();
                    for (ResolveInfo ri : list) {
                        String pkg = ri.activityInfo.packageName;
                        if (!vus.add(pkg)) continue;
                        JSONObject o = new JSONObject();
                        o.put("p", pkg);
                        o.put("l", String.valueOf(ri.loadLabel(pm)));
                        o.put("i", iconeEnBase64(ri.loadIcon(pm)));
                        arr.put(o);
                    }
                    final String js = "window.setApps && window.setApps(" + arr.toString() + ")";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            web.evaluateJavascript(js, null);
                        }
                    });
                } catch (Exception e) {
                    // La liste reste vide.
                }
            }
        }).start();
    }

    // ---------- ZLink (lecture seule : on ne le modifie jamais) ----------

    private String zlinkPackage() {
        return findByKeywords(new String[]{"zlink"});
    }

    private void sendZlink() {
        final String js = "window.setZlink && window.setZlink(" + (zlinkPackage() != null) + ")";
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private String zlinkInfo() {
        String pkg = zlinkPackage();
        if (pkg == null) {
            return "ZLink introuvable sur ce poste.";
        }
        String version = "inconnue";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            if (pi.versionName != null) version = pi.versionName;
        } catch (Exception e) {
            // version inconnue
        }
        PackageManager pm = getPackageManager();
        String fen = "\nFenêtres libres : " + (pm.hasSystemFeature("android.software.freeform_window_management") ? "oui" : "non")
                + "\nImage dans l'image : " + (pm.hasSystemFeature("android.software.picture_in_picture") ? "oui" : "non");
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return "Paquet : " + pkg + "\nVersion : " + version
                + "\nAndroid : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nÉcran : " + dm.widthPixels + "x" + dm.heightPixels + fen
                + "\nModèle : " + Build.MANUFACTURER + " " + Build.MODEL;
    }

    // ---------- Luminosité, volume, Bluetooth ----------

    private void appliquerLuminosite(int pct) {
        if (pct < 5) pct = 5;
        if (pct > 100) pct = 100;
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = pct / 100f;
            getWindow().setAttributes(lp);
        } catch (Exception e) {
            // On ignore.
        }
        try {
            if (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this)) {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, pct * 255 / 100);
            }
        } catch (Exception e) {
            // Réglage système refusé : seul le launcher est concerné.
        }
    }

    private BluetoothAdapter btAdapter() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean btPermissionOk() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void ouvrirReglagesBluetooth() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Réglages Bluetooth introuvables sur ce poste", Toast.LENGTH_SHORT).show();
        }
    }

    private void envoyerDock(final String json) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PackageManager pm = getPackageManager();
                    JSONArray in = new JSONArray(json);
                    JSONArray out = new JSONArray();
                    for (int n = 0; n < in.length(); n++) {
                        JSONObject o = in.getJSONObject(n);
                        String id = o.getString("id");
                        String pkg = prefs.getString("app_" + id, null);
                        if (pkg == null) pkg = findByKeywords(o.getString("kw").split(","));
                        if (pkg == null) continue;
                        JSONObject r = new JSONObject();
                        r.put("id", id);
                        r.put("i", iconeEnBase64(pm.getApplicationIcon(pkg)));
                        out.put(r);
                    }
                    final String js = "window.setDock && window.setDock(" + out.toString() + ")";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            web.evaluateJavascript(js, null);
                        }
                    });
                } catch (Exception e) {
                    // Le dock garde ses icônes par défaut.
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Diagnostic CAN : LECTURE SEULE. On regarde ce que le poste expose,
    // on n'envoie aucune commande à la voiture ni au boîtier CAN.
    // ------------------------------------------------------------------
    private static final String[] MOTS_PAQUETS = {
            "canbus", ".can", "can.", "mcu", "vehicle", "carinfo", "hvac", "tpms",
            "obd", "door", "climate", "syu", "fyt", "zjinnova", "carset", "cardata"};
    private static final String[] MOTS_REGLAGES = {
            "door", "canbus", "can_", "volt", "fuel", "hvac", "mcu", "reverse",
            "tpms", "brake", "car_", "vehicle", "outtemp", "ac_"};
    private final List<String> actionsCan = new ArrayList<String>();
    private BroadcastReceiver recepteurCan;

    private static boolean contientUn(String texte, String[] mots) {
        String b = texte.toLowerCase(Locale.ROOT);
        for (String m : mots) {
            if (b.contains(m)) return true;
        }
        return false;
    }

    private void envoyerRapportCan(String type, String texte) {
        final String js = "window.setCanReport && window.setCanReport("
                + JSONObject.quote(type) + "," + JSONObject.quote(texte) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    // Photographie de toutes les propriétés du poste et des réglages Android (lecture seule).
    private java.util.Map<String, String> photoEtat() {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        try {
            Process pr = Runtime.getRuntime().exec("getprop");
            BufferedReader br = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String l;
            while ((l = br.readLine()) != null) {
                int a = l.indexOf("]: [");
                if (l.startsWith("[") && a > 0) {
                    m.put("prop " + l.substring(1, a), l.substring(a + 4, l.length() - (l.endsWith("]") ? 1 : 0)));
                }
            }
            br.close();
        } catch (Exception e) { }
        String[] tables = {"system", "global", "secure"};
        for (String tb : tables) {
            try {
                Cursor c = getContentResolver().query(Uri.parse("content://settings/" + tb),
                        new String[]{"name", "value"}, null, null, null);
                if (c != null) {
                    while (c.moveToNext()) m.put(tb + " " + c.getString(0), c.getString(1));
                    c.close();
                }
            } catch (Exception e) { }
        }
        return m;
    }

    private boolean cleBruyante(String k) {
        String s = k.toLowerCase(Locale.ROOT);
        return s.contains("statistics") || s.contains("time") || s.contains("uptime")
                || s.contains("ticks") || s.contains("wifi") || s.contains("ble_") || s.contains("battery_stats")
                || s.contains("sys.") || s.contains("boot") || s.contains("netd");
    }

    // Observe 60 s : note toute propriété qui change quand tu ouvres une porte / changes le chauffage.
    private void observerCan() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.Map<String, String> avant = photoEtat();
                StringBuilder sb = new StringBuilder();
                long debut = System.currentTimeMillis();
                int dernierEnvoi = 0;
                while (System.currentTimeMillis() - debut < 60000) {
                    try { Thread.sleep(900); } catch (Exception e) { }
                    java.util.Map<String, String> apres = photoEtat();
                    long s = (System.currentTimeMillis() - debut) / 1000;
                    for (java.util.Map.Entry<String, String> en : apres.entrySet()) {
                        String k = en.getKey(), v = en.getValue(), o = avant.get(k);
                        if (cleBruyante(k)) continue;
                        if (o == null || !o.equals(v)) {
                            if (sb.length() < 5000) {
                                sb.append("+").append(s).append("s ").append(k).append(" : ")
                                  .append(o == null ? "(nouveau)" : o).append(" -> ").append(v).append("\n");
                            }
                        }
                    }
                    avant = apres;
                    if (sb.length() != dernierEnvoi) {
                        dernierEnvoi = sb.length();
                        envoyerRapportCan("watch", "Observation en cours…\n" + sb);
                    }
                }
                envoyerRapportCan("watch", sb.length() == 0
                        ? "Observation terminée : aucun changement détecté."
                        : "Observation terminée. Changements :\n" + sb);
            }
        }).start();
    }

    private void listerComposants(StringBuilder sb, String titre, ComponentInfo[] liste) {
        if (liste == null || liste.length == 0) return;
        sb.append("  ").append(titre).append(" : ");
        int n = 0;
        for (ComponentInfo c : liste) {
            if (n++ >= 12) {
                sb.append("… (+").append(liste.length - 12).append(")");
                break;
            }
            String nom = c.name == null ? "?" : c.name;
            int i = nom.lastIndexOf('.');
            sb.append(i >= 0 ? nom.substring(i + 1) : nom).append(c.exported ? "[ext] " : " ");
        }
        sb.append('\n');
    }

    private void actionsDuManifeste(String paquet) {
        try {
            Context c = createPackageContext(paquet, 0);
            XmlResourceParser p = c.getAssets().openXmlResourceParser("AndroidManifest.xml");
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "action".equals(p.getName())) {
                    String a = p.getAttributeValue("http://schemas.android.com/apk/res/android", "name");
                    if (a != null && !a.startsWith("android.intent.action.MAIN")
                            && !actionsCan.contains(a) && actionsCan.size() < 250) {
                        actionsCan.add(a);
                    }
                }
            }
            p.close();
        } catch (Exception e) {
            // Manifeste illisible : on passe.
        }
    }

    private String rapportCan() {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = getPackageManager();
        actionsCan.clear();
        sb.append("1) APPLICATIONS LIÉES AU VÉHICULE\n");
        try {
            List<PackageInfo> toutes = pm.getInstalledPackages(PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
            int n = 0;
            for (PackageInfo pi : toutes) {
                String pkg = pi.packageName;
                if (!contientUn(pkg, MOTS_PAQUETS)) continue;
                if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.")) continue;
                if (n++ >= 14) {
                    sb.append("… (liste coupée)\n");
                    break;
                }
                String nom = "";
                try {
                    nom = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
                } catch (Exception e) {
                    // Pas de nom.
                }
                sb.append("• ").append(pkg).append(" (").append(nom).append(") v")
                        .append(pi.versionName).append('\n');
                listerComposants(sb, "Services", pi.services);
                listerComposants(sb, "Récepteurs", pi.receivers);
                listerComposants(sb, "Fournisseurs", pi.providers);
                actionsDuManifeste(pkg);
            }
            if (n == 0) sb.append("Aucune application évidente trouvée.\n");
        } catch (Exception e) {
            sb.append("Erreur : ").append(e).append('\n');
        }

        sb.append("\n2) MESSAGES SYSTÈME ÉCOUTABLES (").append(actionsCan.size()).append(")\n");
        int k = 0;
        for (String a : actionsCan) {
            if (k++ >= 40) {
                sb.append("…\n");
                break;
            }
            sb.append(a).append('\n');
        }

        sb.append("\n3) RÉGLAGES ANDROID UTILES\n");
        Uri[] uris = {Settings.System.CONTENT_URI, Settings.Global.CONTENT_URI, Settings.Secure.CONTENT_URI};
        String[] noms = {"system", "global", "secure"};
        int total = 0;
        for (int i = 0; i < uris.length; i++) {
            Cursor cur = null;
            try {
                cur = getContentResolver().query(uris[i], new String[]{"name", "value"}, null, null, null);
                while (cur != null && cur.moveToNext() && total < 40) {
                    String n = cur.getString(0);
                    if (n != null && contientUn(n, MOTS_REGLAGES)) {
                        String v = String.valueOf(cur.getString(1));
                        if (v.length() > 40) v = v.substring(0, 40);
                        sb.append(noms[i]).append(": ").append(n).append('=').append(v).append('\n');
                        total++;
                    }
                }
            } catch (Exception e) {
                // Table illisible : on passe.
            } finally {
                if (cur != null) cur.close();
            }
        }
        if (total == 0) sb.append("Rien de trouvé.\n");

        sb.append("\n4) PROPRIÉTÉS DU POSTE\n");
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"getprop"});
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String ligne;
            int m = 0;
            while ((ligne = r.readLine()) != null && m < 30) {
                String b = ligne.toLowerCase(Locale.ROOT);
                if (b.contains("canbus") || b.contains("mcu") || b.contains("vehicle")
                        || b.contains("door") || b.contains("hvac") || b.contains("syu")
                        || b.contains("fyt") || b.contains("zjinnova")) {
                    sb.append(ligne).append('\n');
                    m++;
                }
            }
            r.close();
            if (m == 0) sb.append("Rien de trouvé.\n");
        } catch (Exception e) {
            sb.append("Illisible.\n");
        }
        return sb.toString();
    }

    // Écoute 60 s des messages système liés au véhicule (porte, chauffage...).
    @SuppressWarnings("deprecation")
    private void ecouterCan() {
        if (recepteurCan != null) return;
        if (actionsCan.isEmpty()) {
            rapportCan();
        }
        if (actionsCan.isEmpty()) {
            envoyerRapportCan("listen", "Aucun message à écouter : fais d'abord l'analyse du poste.");
            return;
        }
        final StringBuilder journal = new StringBuilder();
        final long debut = System.currentTimeMillis();
        recepteurCan = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent in) {
                if (journal.length() > 6000) return;
                long s = (System.currentTimeMillis() - debut) / 1000;
                journal.append(s).append(" s  ").append(in.getAction()).append('\n');
                Bundle ex = in.getExtras();
                if (ex != null) {
                    int c = 0;
                    for (String cle : ex.keySet()) {
                        if (c++ >= 8) break;
                        String v = String.valueOf(ex.get(cle));
                        if (v.length() > 60) v = v.substring(0, 60);
                        journal.append("    ").append(cle).append('=').append(v).append('\n');
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : actionsCan) f.addAction(a);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(recepteurCan, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(recepteurCan, f);
            }
        } catch (Exception e) {
            recepteurCan = null;
            envoyerRapportCan("listen", "Écoute impossible : " + e);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (recepteurCan != null) unregisterReceiver(recepteurCan);
                } catch (Exception e) {
                    // Déjà arrêté.
                }
                recepteurCan = null;
                envoyerRapportCan("listen", journal.length() == 0
                        ? "Aucun message reçu pendant 60 s."
                        : journal.toString());
            }
        }, 60000);
    }

    private class Bridge {
        @JavascriptInterface
        public void open(final String id, final String keywords) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    launchApp(id, keywords.split(","));
                }
            });
        }

        @JavascriptInterface
        public void choose(final String id) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pick(id);
                }
            });
        }

        @JavascriptInterface
        public void launchPackage(final String pkg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.launchPackage(pkg, null);
                }
            });
        }

        @JavascriptInterface
        public void requestApps() {
            envoyerApps();
        }

        @JavascriptInterface
        public String getVoice() {
            return (prefs.getBoolean("voice_on", true) ? "1" : "0") + "," + prefs.getInt("voice_vol", 90);
        }

        @JavascriptInterface
        public void setVoice(boolean on, int volume) {
            prefs.edit().putBoolean("voice_on", on).putInt("voice_vol", volume).apply();
        }

        @JavascriptInterface
        public void testVoice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ttsPret) {
                        saluer();
                    } else {
                        Toast.makeText(MainActivity.this, "Voix indisponible : installe le français dans la synthèse vocale",
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void requestDock(String json) {
            envoyerDock(json);
        }

        @JavascriptInterface
        public String getBrightness() {
            return String.valueOf(prefs.getInt("bright", 80));
        }

        @JavascriptInterface
        public void setBrightness(final int pct) {
            prefs.edit().putInt("bright", pct).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    appliquerLuminosite(pct);
                }
            });
        }

        @JavascriptInterface
        public boolean canWrite() {
            return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(MainActivity.this);
        }

        @JavascriptInterface
        public void requestWrite() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Réglage indisponible sur ce poste", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public int getVolume() {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                return max <= 0 ? 50 : am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max;
            } catch (Exception e) {
                return 50;
            }
        }

        @JavascriptInterface
        public void setVolume(int pct) {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), 0);
            } catch (Exception e) {
                // Volume géré par le boîtier du poste : on ignore.
            }
        }

        @JavascriptInterface
        public String btState() {
            BluetoothAdapter a = btAdapter();
            if (a == null) return "na";
            try {
                return a.isEnabled() ? "on" : "off";
            } catch (Exception e) {
                return "na";
            }
        }

        @JavascriptInterface
        public String btDevices() {
            JSONArray arr = new JSONArray();
            try {
                BluetoothAdapter a = btAdapter();
                if (a != null && btPermissionOk()) {
                    for (BluetoothDevice d : a.getBondedDevices()) {
                        String n = d.getName();
                        if (n != null) arr.put(n);
                    }
                }
            } catch (Exception e) {
                // Liste vide.
            }
            return arr.toString();
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void btSet(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BluetoothAdapter a = btAdapter();
                    if (a == null) return;
                    try {
                        if (Build.VERSION.SDK_INT >= 33 || !btPermissionOk()) {
                            // Android 13+ n'autorise plus une appli à le faire : on ouvre les réglages.
                            ouvrirReglagesBluetooth();
                        } else if (on) {
                            a.enable();
                        } else {
                            a.disable();
                        }
                    } catch (Exception e) {
                        ouvrirReglagesBluetooth();
                    }
                }
            });
        }

        @JavascriptInterface
        public void btSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ouvrirReglagesBluetooth();
                }
            });
        }

        // Diagnostic CAN (lecture seule).
        @JavascriptInterface
        public void canScan() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    envoyerRapportCan("scan", rapportCan());
                }
            }).start();
        }

        @JavascriptInterface
        public void canListen() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ecouterCan();
                }
            });
        }

        @JavascriptInterface
        public void canWatch() {
            observerCan();
        }

        // Ouvre les vrais réglages Android du poste.
        @JavascriptInterface
        public void androidSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Réglages indisponibles", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void testFloat() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    essaiFenetreZlink();
                }
            });
        }

        @JavascriptInterface
        public String zlinkInfo() {
            return MainActivity.this.zlinkInfo();
        }

        // Vrai la première fois seulement (animation de démarrage), faux ensuite.
        @JavascriptInterface
        public boolean splashNeeded() {
            if (demarrageJoue) return false;
            demarrageJoue = true;
            return true;
        }

        // Résolution réelle de l'écran vue par Android (lecture seule).
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public String screenInfo() {
            try {
                DisplayMetrics real = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(real);
                DisplayMetrics app = getResources().getDisplayMetrics();
                return "Écran réel (Android) : " + real.widthPixels + " × " + real.heightPixels
                        + " px, " + real.densityDpi + " dpi\n"
                        + "Zone de l'application : " + app.widthPixels + " × " + app.heightPixels
                        + " px (densité " + app.density + ")";
            } catch (Exception e) {
                return "Indisponible";
            }
        }

        // "wifi", "mobile", "none" ou "na" : lecture seule de l'état du réseau.
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public String netInfo() {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm == null) return "na";
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni == null || !ni.isConnected()) return "none";
                if (ni.getType() == ConnectivityManager.TYPE_WIFI) return "wifi";
                return "mobile";
            } catch (Exception e) {
                return "na";
            }
        }
    }
}
