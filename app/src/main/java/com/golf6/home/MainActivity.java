package com.golf6.home;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.media.AudioManager;
import android.media.MediaPlayer;
import java.io.File;
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
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.content.ActivityNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
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
    private static final String PRENOM = ""; // valeur par défaut, modifiable dans Réglages > Ma voiture
    // Délai (ms) avant la phrase, pour la caler sur l'animation de démarrage.
    private static final long DELAI_VOIX = 300;

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
            Toast.makeText(this, L("Fenêtre non prise en charge par ce poste", "Window not supported by this unit"), Toast.LENGTH_LONG).show();
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
                    int r = tts.setLanguage(localeVoix());
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        return;
                    }
                    if ("fr".equals(langue())) {
                        choisirVoixFeminine();
                    }
                    tts.setSpeechRate(0.95f);
                    ttsPret = true;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            preparerSalutations();
                        }
                    }, 6000);
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
                    if (jouerSalutCache()) {
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

    private String langue() {
        String l = prefs.getString("lang", "fr");
        return l == null ? "fr" : l;
    }

    /** Petit utilitaire : texte français ou anglais selon la langue choisie. */
    private String L(String fr, String en) {
        return "fr".equals(langue()) ? fr : en;
    }

    private String prenom() {
        String s = prefs.getString("prenom", PRENOM);
        return s == null ? "" : s.trim();
    }

    private Locale localeVoix() {
        String l = langue();
        return "fr".equals(l) ? Locale.FRANCE : new Locale(l);
    }

    private String phraseSalutation() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return phrasePour((h >= 5 && h < 12) ? 0 : ((h >= 12 && h < 18) ? 1 : 2));
    }

    private String phrasePour(int p) {
        String l = langue();
        String[] mots;
        if ("en".equals(l)) {
            mots = new String[]{"Good morning", "Good afternoon", "Good evening"};
        } else if ("es".equals(l)) {
            mots = new String[]{"Buenos días", "Buenas tardes", "Buenas noches"};
        } else if ("de".equals(l)) {
            mots = new String[]{"Guten Morgen", "Guten Tag", "Guten Abend"};
        } else if ("it".equals(l)) {
            mots = new String[]{"Buongiorno", "Buon pomeriggio", "Buonasera"};
        } else if ("pt".equals(l)) {
            mots = new String[]{"Bom dia", "Boa tarde", "Boa noite"};
        } else if ("nl".equals(l)) {
            mots = new String[]{"Goedemorgen", "Goedemiddag", "Goedenavond"};
        } else if ("pl".equals(l)) {
            mots = new String[]{"Dzień dobry", "Dzień dobry", "Dobry wieczór"};
        } else if ("ro".equals(l)) {
            mots = new String[]{"Bună dimineața", "Bună ziua", "Bună seara"};
        } else if ("tr".equals(l)) {
            mots = new String[]{"Günaydın", "İyi günler", "İyi akşamlar"};
        } else {
            mots = new String[]{"Bonjour", "Bon après-midi", "Bonsoir"};
        }
        String n = prenom();
        return n.isEmpty() ? mots[p] : mots[p] + " " + n;
    }

    // ---- Phrase pré-enregistrée : jouée tout de suite, sans attendre la voix de synthèse ----
    private MediaPlayer salutPlayer;

    private final Runnable regenerer = new Runnable() {
        @Override
        public void run() {
            preparerSalutations();
        }
    };

    private File fichierSalut(String phrase) {
        return new File(getCacheDir(), "salut_" + Integer.toHexString((langue() + "|" + phrase).hashCode()) + ".wav");
    }

    private boolean jouerSalutCache() {
        try {
            File f = fichierSalut(phraseSalutation());
            if (!f.exists() || f.length() < 2000) {
                return false;
            }
            if (salutPlayer != null) {
                try {
                    salutPlayer.release();
                } catch (Exception e) {
                    // ignoré
                }
            }
            salutPlayer = new MediaPlayer();
            salutPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            salutPlayer.setDataSource(f.getAbsolutePath());
            salutPlayer.prepare();
            float vol = prefs.getInt("voice_vol", 90) / 100f;
            salutPlayer.setVolume(vol, vol);
            salutPlayer.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Enregistre les trois phrases (matin, après-midi, soir) pour les jouer instantanément au prochain démarrage. */
    private void preparerSalutations() {
        if (!ttsPret || tts == null) {
            return;
        }
        try {
            tts.setLanguage(localeVoix());
            for (int p = 0; p < 3; p++) {
                String phrase = phrasePour(p);
                File f = fichierSalut(phrase);
                if (!f.exists() || f.length() < 2000) {
                    tts.synthesizeToFile(phrase, new Bundle(), f, "cache" + p);
                }
            }
        } catch (Exception e) {
            // Facultatif : sans cache, la voix de synthèse prend le relais.
        }
    }

    private void saluer() {
        if (jouerSalutCache()) {
            return;
        }
        try {
            Bundle params = new Bundle();
            float vol = prefs.getInt("voice_vol", 90) / 100f;
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol);
            try {
                tts.setLanguage(localeVoix());
            } catch (Exception e2) {
                // On garde la langue actuelle de la voix.
            }
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
        // Retour : ferme la fenêtre ouverte ou revient à l'accueil (géré par l'interface).
        try { web.evaluateJavascript("window.goBack && window.goBack()", null); } catch (Exception e) { }
    }

    // ---------- Alerte de vitesse, parole ----------

    private boolean alerteDeclenchee = false;

    private String phraseAlerte() {
        String l = langue();
        if ("en".equals(l)) return "Warning, high speed";
        if ("es".equals(l)) return "Atención, velocidad elevada";
        if ("de".equals(l)) return "Achtung, hohe Geschwindigkeit";
        if ("it".equals(l)) return "Attenzione, velocità elevata";
        if ("pt".equals(l)) return "Atenção, velocidade elevada";
        if ("nl".equals(l)) return "Let op, hoge snelheid";
        if ("pl".equals(l)) return "Uwaga, wysoka prędkość";
        if ("ro".equals(l)) return "Atenție, viteză ridicată";
        if ("tr".equals(l)) return "Dikkat, yüksek hız";
        return "Attention, vitesse élevée";
    }

    private void parler(String texte) {
        if (!ttsPret || tts == null || texte == null || !prefs.getBoolean("voice_on", true)) {
            return;
        }
        try {
            Bundle params = new Bundle();
            float vol = prefs.getInt("voice_vol", 90) / 100f;
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol);
            try {
                tts.setLanguage(localeVoix());
            } catch (Exception e2) {
                // On garde la langue actuelle de la voix.
            }
            tts.speak(texte, TextToSpeech.QUEUE_FLUSH, params, "alerte");
        } catch (Exception e) {
            // La voix est facultative.
        }
    }

    // ---------- Fond d'écran personnalisé ----------

    private static final int REQ_FOND = 7711;
    private static final int REQ_EXPORT = 7712;
    private static final int REQ_IMPORT = 7713;
    private String exportEnAttente = null;
    private String fondSlot = "jour";

    private void lancerInstallation() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                fondJs("window.updErr&&window.updErr('droit')");
                Intent r = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(r);
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("content://com.golf6.home.maj/cockpit-maj.apk"),
                    "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            fondJs("window.updErr&&window.updErr('install')");
        }
    }

    private void fondJs(final String js) {
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private void enregistrerFond(Bitmap src, String nom, float r, float g, float b) throws Exception {
        Bitmap out = src;
        if (r != 1f || g != 1f || b != 1f) {
            out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            ColorMatrix cm = new ColorMatrix(new float[]{
                    r, 0, 0, 0, 0,
                    0, g, 0, 0, 0,
                    0, 0, b, 0, 0,
                    0, 0, 0, 1, 0});
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            c.drawBitmap(src, 0, 0, p);
        }
        FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), nom));
        try {
            out.compress(Bitmap.CompressFormat.JPEG, 88, fo);
        } finally {
            fo.close();
        }
    }

    private void traiterFond(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    InputStream in = getContentResolver().openInputStream(uri);
                    BitmapFactory.decodeStream(in, null, o);
                    in.close();
                    int ech = 1;
                    while (o.outWidth / (ech * 2) >= 1920 && o.outHeight / (ech * 2) >= 1080) {
                        ech *= 2;
                    }
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = ech;
                    in = getContentResolver().openInputStream(uri);
                    Bitmap full = BitmapFactory.decodeStream(in, null, o2);
                    in.close();
                    if (full == null) {
                        throw new Exception("image");
                    }
                    int w = full.getWidth(), h = full.getHeight();
                    int cw = w, ch = h;
                    if (w * 9 > h * 16) {
                        cw = h * 16 / 9;
                    } else {
                        ch = w * 9 / 16;
                    }
                    Bitmap crop = Bitmap.createBitmap(full, (w - cw) / 2, (h - ch) / 2, cw, ch);
                    Bitmap fin = Bitmap.createScaledBitmap(crop, 1280, 720, true);
                    String slot = fondSlot;
                    enregistrerFond(fin, "fond_" + slot + ".jpg", 1f, 1f, 1f);
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putBoolean("bg_u_" + slot, true);
                    if ("jour".equals(slot)) {
                        // Les versions de nuit non choisies sont déduites de la photo de jour.
                        if (!prefs.getBoolean("bg_u_on", false)) {
                            enregistrerFond(fin, "fond_on.jpg", 0.42f, 0.48f, 0.68f);
                        }
                        if (!prefs.getBoolean("bg_u_off", false)) {
                            enregistrerFond(fin, "fond_off.jpg", 0.26f, 0.30f, 0.46f);
                        }
                    }
                    ed.putLong("bg_stamp", System.currentTimeMillis()).apply();
                    fondJs("window.bgDone&&window.bgDone(true)");
                } catch (Throwable e) {
                    fondJs("window.bgDone&&window.bgDone(false)");
                }
            }
        }).start();
    }

    private String lireFondBase64(String nom) {
        try {
            File f = new File(getFilesDir(), nom);
            if (!f.exists()) {
                return "";
            }
            byte[] buf = new byte[(int) f.length()];
            InputStream in = new java.io.FileInputStream(f);
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            in.close();
            return "data:image/jpeg;base64," + Base64.encodeToString(buf, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (req == REQ_FOND) {
            traiterFond(uri);
        } else if (req == REQ_EXPORT) {
            final String texte = exportEnAttente;
            exportEnAttente = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ok = false;
                    try {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        os.write(texte.getBytes("UTF-8"));
                        os.close();
                        ok = true;
                    } catch (Exception e) {
                        // message d'erreur côté page
                    }
                    fondJs("window.exportDone&&window.exportDone(" + ok + ")");
                }
            }).start();
        } else if (req == REQ_IMPORT) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = getContentResolver().openInputStream(uri);
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = in.read(b)) > 0 && bo.size() < 300000) {
                            bo.write(b, 0, n);
                        }
                        in.close();
                        String t = bo.toString("UTF-8");
                        fondJs("window.importDone&&window.importDone(" + JSONObject.quote(t) + ")");
                    } catch (Exception e) {
                        fondJs("window.importDone&&window.importDone(null)");
                    }
                }
            }).start();
        }
    }

    // ---------- Vitesse GPS (widget d'accueil) ----------

    private LocationListener gpsListener;

    private void gpsActif(boolean on) {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (gpsListener != null) {
                lm.removeUpdates(gpsListener);
                gpsListener = null;
            }
            if (!on || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return;
            }
            gpsListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location l) {
                    float v = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
                    float b = l.hasBearing() ? l.getBearing() : -1f;
                    double alt = l.hasAltitude() ? l.getAltitude() : -9999d;
                    int seuil = prefs.getInt("speed_alert", 0);
                    if (seuil > 0) {
                        if (v > seuil && !alerteDeclenchee) {
                            alerteDeclenchee = true;
                            final String ph = phraseAlerte();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    parler(ph);
                                }
                            });
                        } else if (v < seuil - 5) {
                            alerteDeclenchee = false;
                        }
                    }
                    final String js = String.format(Locale.US,
                            "window.setGps && window.setGps(%.1f,%.0f,%.0f)", v, b, alt);
                    web.post(new Runnable() {
                        @Override
                        public void run() {
                            web.evaluateJavascript(js, null);
                        }
                    });
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener);
        } catch (SecurityException e) {
            // Pas de permission de localisation : le widget affiche « -- ».
        } catch (Exception e) {
            // Le GPS est facultatif.
        }
    }

    // ---------- Lecteur de musique (widget facultatif) ----------

    private boolean accesNotifications() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private MediaController controleurActif() {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            java.util.List<MediaController> l = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            MediaController premier = null;
            for (MediaController c : l) {
                if (premier == null) {
                    premier = c;
                }
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    return c;
                }
            }
            return premier;
        } catch (Exception e) {
            return null;
        }
    }

    private String infosMusique() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            boolean acces = accesNotifications();
            o.put("access", acces);
            if (acces) {
                MediaController c = controleurActif();
                if (c != null) {
                    MediaMetadata m = c.getMetadata();
                    String titre = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
                    String artiste = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    PlaybackState st = c.getPlaybackState();
                    o.put("title", titre == null ? "" : titre);
                    o.put("artist", artiste == null ? "" : artiste);
                    o.put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING);
                    o.put("has", true);
                } else {
                    o.put("has", false);
                }
            }
        } catch (Exception e) {
            // On renvoie ce qu'on a.
        }
        return o.toString();
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
                .setTitle(L("Choisis l'application à ouvrir", "Choose the app to open"))
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
            Toast.makeText(this, L("Réglages Bluetooth introuvables sur ce poste", "Bluetooth settings not found on this unit"), Toast.LENGTH_SHORT).show();
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
                StringBuilder prio = new StringBuilder();
                StringBuilder autres = new StringBuilder();
                long debut = System.currentTimeMillis();
                int dernier = 0;
                while (System.currentTimeMillis() - debut < 60000) {
                    try { Thread.sleep(700); } catch (Exception e) { }
                    java.util.Map<String, String> apres = photoEtat();
                    long s = (System.currentTimeMillis() - debut) / 1000;
                    for (java.util.Map.Entry<String, String> en : apres.entrySet()) {
                        String k = en.getKey(), v = en.getValue(), o = avant.get(k);
                        if (v == null || o == null || o.equals(v)) continue;
                        String kl = k.toLowerCase(Locale.ROOT);
                        if (cleBruyante(k) || kl.contains("gps")) continue;
                        String ligne = "+" + s + "s " + k + " : " + o + " -> " + v + "\n";
                        boolean important = kl.contains("cem") || kl.contains("door") || kl.contains("can")
                                || kl.contains("air") || kl.contains("temp") || kl.contains("heat")
                                || kl.contains("hvac") || kl.contains("fan") || kl.contains("vendor") || kl.contains("mcu");
                        if (important) { if (prio.length() < 3500) prio.append(ligne); }
                        else if (autres.length() < 1500) autres.append(ligne);
                    }
                    avant = apres;
                    int n = prio.length() + autres.length();
                    if (n != dernier) {
                        dernier = n;
                        envoyerRapportCan("watch", "Observation en cours…\n" + prio + (autres.length() > 0 ? "--- autres ---\n" + autres : ""));
                    }
                }
                envoyerRapportCan("watch", (prio.length() + autres.length() == 0)
                        ? "Observation terminée : aucun changement détecté."
                        : "Observation terminée.\n" + prio + (autres.length() > 0 ? "--- autres ---\n" + autres : ""));
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


    // ---------- Assistant de détection des infos de la voiture (lecture seule) ----------
    private final java.util.concurrent.ConcurrentHashMap<String, String> etatBc =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private BroadcastReceiver recepteurDetect;
    private final java.util.Map<String, java.util.Map<String, String>> photosDetect =
            new java.util.HashMap<String, java.util.Map<String, String>>();
    private final Set<String> bruitDetect = java.util.Collections.synchronizedSet(new HashSet<String>());
    private volatile boolean surveillance = false;
    private final List<String> clesSurveillees = java.util.Collections.synchronizedList(new ArrayList<String>());

    private java.util.Map<String, String> etatComplet() {
        java.util.Map<String, String> m = photoEtat();
        m.putAll(etatBc);
        return m;
    }

    private void envoyerDetect(String type, String json) {
        final String js = "window.onDetect && window.onDetect(" + JSONObject.quote(type) + "," + json + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void enregistrerRecepteurDetect() {
        if (recepteurDetect != null || actionsCan.isEmpty()) return;
        recepteurDetect = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent in) {
                String a = in.getAction();
                if (a == null) return;
                String n = etatBc.get("bc " + a);
                int c = 0;
                try { c = n == null ? 0 : Integer.parseInt(n); } catch (Exception e) { }
                etatBc.put("bc " + a, String.valueOf(c + 1));
                Bundle ex = in.getExtras();
                if (ex != null) {
                    int k = 0;
                    for (String cle : ex.keySet()) {
                        if (k++ >= 20) break;
                        String v = String.valueOf(ex.get(cle));
                        if (v.length() > 80) v = v.substring(0, 80);
                        etatBc.put("bc " + a + " " + cle, v);
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : actionsCan) f.addAction(a);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(recepteurDetect, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(recepteurDetect, f);
        } catch (Exception e) {
            recepteurDetect = null;
        }
    }

    private void demarrerDetection() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (actionsCan.isEmpty()) {
                    try { rapportCan(); } catch (Exception e) { }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        enregistrerRecepteurDetect();
                        envoyerDetect("ready", "{\"n\":" + actionsCan.size() + "}");
                    }
                });
            }
        }).start();
    }

    private static boolean contientMot(String k, String[] mots) {
        String b = k.toLowerCase(Locale.ROOT);
        for (String m : mots) if (b.contains(m)) return true;
        return false;
    }

    private static String[] motsPour(String id) {
        if (id.startsWith("door")) return new String[]{"door", "porte", "cem", "dr_", "_dr", "open"};
        if (id.startsWith("light")) return new String[]{"light", "lamp", "lgt", "head", "ill", "beam", "lowbeam", "dipped"};
        if (id.equals("temp")) return new String[]{"temp", "outside", "out_", "outdoor", "amb", "ext"};
        if (id.equals("odo")) return new String[]{"odo", "mile", "km", "total", "mileage", "distance"};
        return new String[]{"can", "car", "vehicle"};
    }

    private static Double nombre(String v) {
        if (v == null) return null;
        String t = v.trim().replace(',', '.');
        if (t.length() == 0 || t.length() > 14) return null;
        try { return Double.valueOf(t); } catch (Exception e) { return null; }
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
        public void setApp(String id, String pkg) {
            if (id != null && id.matches("[a-z]{2,12}") && pkg != null && pkg.matches("[A-Za-z0-9._]{3,120}")
                    && getPackageManager().getLaunchIntentForPackage(pkg) != null) {
                prefs.edit().putString("app_" + id, pkg).apply();
            }
        }

        @JavascriptInterface
        public String guessApp(String id, String keywords) {
            try {
                String pkg = prefs.getString("app_" + id, null);
                if (pkg == null) {
                    pkg = findByKeywords(keywords.split(","));
                }
                return pkg == null ? "" : pkg;
            } catch (Exception e) {
                return "";
            }
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
        public void setLang(String code) {
            if (code != null && code.matches("[a-z]{2}")) {
                String ancien = prefs.getString("lang", "fr");
                prefs.edit().putString("lang", code).apply();
                if (!code.equals(ancien)) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            preparerSalutations();
                        }
                    }, 1500);
                }
            }
        }

        @JavascriptInterface
        public void gpsOn(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    gpsActif(on);
                }
            });
        }

        @JavascriptInterface
        public String getMedia() {
            return infosMusique();
        }

        @JavascriptInterface
        public void mediaCmd(final String cmd) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        MediaController c = controleurActif();
                        if (c == null) {
                            return;
                        }
                        if ("next".equals(cmd)) {
                            c.getTransportControls().skipToNext();
                        } else if ("prev".equals(cmd)) {
                            c.getTransportControls().skipToPrevious();
                        } else {
                            PlaybackState st = c.getPlaybackState();
                            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                                c.getTransportControls().pause();
                            } else {
                                c.getTransportControls().play();
                            }
                        }
                    } catch (Exception e) {
                        // Commande ignorée.
                    }
                }
            });
        }

        @JavascriptInterface
        public void openNotifAccess() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                                L("Réglage indisponible sur ce poste", "Setting not available on this unit"),
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void checkUpdate(final String adresse) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String texte = "";
                    try {
                        if (adresse != null && adresse.startsWith("https://")) {
                            HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
                            c.setConnectTimeout(8000);
                            c.setReadTimeout(8000);
                            InputStream in = c.getInputStream();
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] b = new byte[2048];
                            int n;
                            while ((n = in.read(b)) > 0 && bo.size() < 20000) {
                                bo.write(b, 0, n);
                            }
                            in.close();
                            texte = bo.toString("UTF-8");
                        }
                    } catch (Exception e) {
                        texte = "";
                    }
                    fondJs("window.updateInfo&&window.updateInfo(" + JSONObject.quote(texte) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void downloadUpdate(final String adresse) {
            if (adresse == null || !adresse.startsWith("https://")) {
                fondJs("window.updErr&&window.updErr('adresse')");
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File out = new File(getCacheDir(), "cockpit-maj.apk");
                        HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(20000);
                        c.setInstanceFollowRedirects(true);
                        if (c.getResponseCode() != 200) {
                            throw new Exception("http " + c.getResponseCode());
                        }
                        long total = c.getContentLength();
                        InputStream in = c.getInputStream();
                        FileOutputStream fo = new FileOutputStream(out);
                        byte[] b = new byte[16384];
                        long fait = 0;
                        int n;
                        int dernier = -1;
                        while ((n = in.read(b)) > 0) {
                            fo.write(b, 0, n);
                            fait += n;
                            int pct = total > 0 ? (int) (fait * 100 / total) : -1;
                            if (pct != dernier) {
                                dernier = pct;
                                fondJs("window.updProg&&window.updProg(" + pct + ")");
                            }
                        }
                        fo.close();
                        in.close();
                        if (out.length() < 100000) {
                            throw new Exception("fichier trop petit");
                        }
                        fondJs("window.updProg&&window.updProg(100)");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                lancerInstallation();
                            }
                        });
                    } catch (Exception e) {
                        fondJs("window.updErr&&window.updErr('reseau')");
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void installUpdate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    lancerInstallation();
                }
            });
        }

        @JavascriptInterface
        public void openUrl(final String adresse) {
            if (adresse == null || !adresse.startsWith("https://")) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(adresse)));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, L("Aucun navigateur sur ce poste",
                                "No browser on this unit"), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void pickBackground(final String slot) {
            if ("jour".equals(slot) || "on".equals(slot) || "off".equals(slot)) {
                fondSlot = slot;
            } else {
                fondSlot = "jour";
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(i, REQ_FOND);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, L("Aucun sélecteur de photos sur ce poste",
                                "No photo picker on this unit"), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public long getBgStamp() {
            return prefs.getLong("bg_stamp", 0L);
        }

        @JavascriptInterface
        public String getBgFlags() {
            return (prefs.getBoolean("bg_u_jour", false) ? "jour," : "")
                    + (prefs.getBoolean("bg_u_on", false) ? "on," : "")
                    + (prefs.getBoolean("bg_u_off", false) ? "off," : "");
        }

        @JavascriptInterface
        public String getBg(String which) {
            if ("jour".equals(which)) return lireFondBase64("fond_jour.jpg");
            if ("on".equals(which)) return lireFondBase64("fond_on.jpg");
            if ("off".equals(which)) return lireFondBase64("fond_off.jpg");
            return "";
        }

        @JavascriptInterface
        public void resetBackground() {
            new File(getFilesDir(), "fond_jour.jpg").delete();
            new File(getFilesDir(), "fond_on.jpg").delete();
            new File(getFilesDir(), "fond_off.jpg").delete();
            prefs.edit().remove("bg_stamp").remove("bg_u_jour").remove("bg_u_on").remove("bg_u_off").apply();
        }

        @JavascriptInterface
        public void setSpeedAlert(int kmh) {
            prefs.edit().putInt("speed_alert", Math.max(0, Math.min(300, kmh))).apply();
            alerteDeclenchee = false;
        }

        @JavascriptInterface
        public int getSpeedAlert() {
            return prefs.getInt("speed_alert", 0);
        }

        @JavascriptInterface
        public void speak(final String texte) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    parler(texte);
                }
            });
        }

        @JavascriptInterface
        public void exportSettings(final String json) {
            exportEnAttente = json;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.setType("application/json");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.putExtra(Intent.EXTRA_TITLE, "accueil-golf6-reglages.json");
                        startActivityForResult(i, REQ_EXPORT);
                    } catch (Exception e) {
                        fondJs("window.exportDone&&window.exportDone(false)");
                    }
                }
            });
        }

        @JavascriptInterface
        public void importSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.setType("*/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(i, REQ_IMPORT);
                    } catch (Exception e) {
                        fondJs("window.importDone&&window.importDone(null)");
                    }
                }
            });
        }

        @JavascriptInterface
        public String getPrenom() {
            return prenom();
        }

        @JavascriptInterface
        public void setPrenom(String nom) {
            String n = nom == null ? "" : nom.replaceAll("[\\p{Cntrl}<>]", "").trim();
            if (n.length() > 24) {
                n = n.substring(0, 24);
            }
            prefs.edit().putString("prenom", n).apply();
            handler.removeCallbacks(regenerer);
            handler.postDelayed(regenerer, 2500);
        }

        @JavascriptInterface
        public void testVoice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ttsPret) {
                        saluer();
                    } else {
                        Toast.makeText(MainActivity.this, L("Voix indisponible : installe la langue choisie dans la synthèse vocale", "Voice unavailable: install the chosen language in the text-to-speech settings"),
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
                        Toast.makeText(MainActivity.this, L("Réglage indisponible sur ce poste", "Setting not available on this unit"), Toast.LENGTH_SHORT).show();
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

        // Sonde rapide : le poste transmet-il des infos de la voiture ?
        @JavascriptInterface
        public void canProbe() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int apps = 0;
                    int actions = 0;
                    try {
                        String r = rapportCan();
                        for (String l : r.split("\n")) {
                            if (l.startsWith("\u2022 ")) apps++;
                        }
                        actions = actionsCan.size();
                    } catch (Exception e) {
                        // On laisse 0.
                    }
                    fondJs("window.onProbe&&window.onProbe(" + apps + "," + actions + ")");
                }
            }).start();
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

        @JavascriptInterface
        public void detectStart() {
            demarrerDetection();
        }

        @JavascriptInterface
        public void detectCalm(final int ms) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    java.util.Map<String, String> base = etatComplet();
                    long t0 = System.currentTimeMillis();
                    while (System.currentTimeMillis() - t0 < Math.max(2000, ms)) {
                        try { Thread.sleep(500); } catch (Exception e) { }
                        java.util.Map<String, String> m = etatComplet();
                        for (java.util.Map.Entry<String, String> en : m.entrySet()) {
                            String o = base.get(en.getKey());
                            if (o == null || !o.equals(en.getValue())) bruitDetect.add(en.getKey());
                        }
                        base = m;
                    }
                    envoyerDetect("calm", "{\"noise\":" + bruitDetect.size() + "}");
                }
            }).start();
        }

        @JavascriptInterface
        public void detectBegin(final String id) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    java.util.Map<String, String> m = etatComplet();
                    synchronized (photosDetect) { photosDetect.put(id, m); }
                    envoyerDetect("begin", "{\"id\":" + JSONObject.quote(id) + "}");
                }
            }).start();
        }

        @JavascriptInterface
        public void detectEnd(final String id) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    java.util.Map<String, String> base;
                    synchronized (photosDetect) { base = photosDetect.get(id); }
                    JSONArray out = new JSONArray();
                    if (base != null) {
                        final java.util.Map<String, String[]> ch = new java.util.LinkedHashMap<String, String[]>();
                        for (int i = 0; i < 6; i++) {
                            java.util.Map<String, String> m = etatComplet();
                            for (java.util.Map.Entry<String, String> en : m.entrySet()) {
                                String k = en.getKey();
                                if (bruitDetect.contains(k) || cleBruyante(k)) continue;
                                String o = base.get(k), v = en.getValue();
                                if (v != null && !v.equals(o)) ch.put(k, new String[]{o == null ? "" : o, v});
                            }
                            try { Thread.sleep(500); } catch (Exception e) { }
                        }
                        final String[] mots = motsPour(id);
                        List<String> cles = new ArrayList<String>(ch.keySet());
                        Collections.sort(cles, new Comparator<String>() {
                            @Override
                            public int compare(String a, String b) {
                                int sa = contientMot(a, mots) ? 0 : 1, sb = contientMot(b, mots) ? 0 : 1;
                                return sa - sb;
                            }
                        });
                        for (String k : cles) {
                            if (out.length() >= 8) break;
                            try {
                                JSONObject o = new JSONObject();
                                o.put("k", k);
                                o.put("o", ch.get(k)[0]);
                                o.put("v", ch.get(k)[1]);
                                o.put("s", contientMot(k, mots) ? 1 : 0);
                                out.put(o);
                            } catch (Exception e) { }
                        }
                    }
                    envoyerDetect("end", "{\"id\":" + JSONObject.quote(id) + ",\"c\":" + out.toString() + "}");
                }
            }).start();
        }

        @JavascriptInterface
        public void detectFind(final String id, final String num) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    JSONArray out = new JSONArray();
                    Double cible = nombre(num);
                    if (cible != null) {
                        boolean temp = id.equals("temp");
                        String[] mots = motsPour(id);
                        String[] fs = temp ? new String[]{"x1", "x0.1", "m40", "h40", "x0.5"}
                                : new String[]{"x1", "x0.1", "x10", "mi", "x0.01"};
                        List<JSONObject> trouv = new ArrayList<JSONObject>();
                        for (java.util.Map.Entry<String, String> en : etatComplet().entrySet()) {
                            Double v = nombre(en.getValue());
                            if (v == null) continue;
                            for (String f : fs) {
                                double c;
                                if (f.equals("x1")) c = v;
                                else if (f.equals("x0.1")) c = v * 0.1;
                                else if (f.equals("x10")) c = v * 10;
                                else if (f.equals("x0.01")) c = v * 0.01;
                                else if (f.equals("x0.5")) c = v * 0.5;
                                else if (f.equals("m40")) c = v - 40;
                                else if (f.equals("h40")) c = v * 0.5 - 40;
                                else c = v * 1.609344;
                                double tol = temp ? 1.6 : Math.max(5, Math.abs(cible) * 0.002);
                                if (Math.abs(c - cible) <= tol && (temp || Math.abs(cible) > 50)) {
                                    try {
                                        JSONObject o = new JSONObject();
                                        o.put("k", en.getKey());
                                        o.put("v", en.getValue());
                                        o.put("f", f);
                                        o.put("s", contientMot(en.getKey(), mots) ? 1 : 0);
                                        trouv.add(o);
                                    } catch (Exception e) { }
                                    break;
                                }
                            }
                        }
                        Collections.sort(trouv, new Comparator<JSONObject>() {
                            @Override
                            public int compare(JSONObject a, JSONObject b) {
                                return b.optInt("s") - a.optInt("s");
                            }
                        });
                        for (JSONObject o : trouv) {
                            if (out.length() >= 8) break;
                            out.put(o);
                        }
                    }
                    envoyerDetect("find", "{\"id\":" + JSONObject.quote(id) + ",\"c\":" + out.toString() + "}");
                }
            }).start();
        }

        @JavascriptInterface
        public void watchKeys(final String json) {
            List<String> l = new ArrayList<String>();
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
            } catch (Exception e) { }
            synchronized (clesSurveillees) {
                clesSurveillees.clear();
                clesSurveillees.addAll(l);
            }
            if (l.isEmpty()) { surveillance = false; return; }
            boolean bc = false;
            for (String k : l) if (k.startsWith("bc ")) bc = true;
            if (bc) demarrerDetection();
            if (surveillance) return;
            surveillance = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    java.util.Map<String, String> avant = new java.util.HashMap<String, String>();
                    while (surveillance) {
                        List<String> cles;
                        synchronized (clesSurveillees) { cles = new ArrayList<String>(clesSurveillees); }
                        boolean props = false;
                        for (String k : cles) if (!k.startsWith("bc ")) props = true;
                        java.util.Map<String, String> m = props ? etatComplet() : new java.util.HashMap<String, String>(etatBc);
                        for (String k : cles) {
                            String v = m.get(k);
                            if (v == null) continue;
                            if (!v.equals(avant.get(k))) {
                                avant.put(k, v);
                                final String js = "window.onCarVal && window.onCarVal(" + JSONObject.quote(k) + "," + JSONObject.quote(v) + ")";
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        web.evaluateJavascript(js, null);
                                    }
                                });
                            }
                        }
                        try { Thread.sleep(1500); } catch (Exception e) { }
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void detectStop() {
            synchronized (photosDetect) { photosDetect.clear(); }
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
                        Toast.makeText(MainActivity.this, L("Réglages indisponibles", "Settings not available"), Toast.LENGTH_SHORT).show();
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

        // Ouvre les réglages wifi du poste (utilisé par l'écran d'activation).
        @JavascriptInterface
        public void openWifi() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent i2 = new Intent(Settings.ACTION_SETTINGS);
                            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i2);
                        } catch (Exception e2) {
                            Toast.makeText(MainActivity.this, L("Réglages indisponibles", "Settings not available"), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        // Définir Cockpit comme écran d'accueil par défaut.
        @JavascriptInterface
        public void setDefaultHome() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            android.app.role.RoleManager rm = (android.app.role.RoleManager) getSystemService(Context.ROLE_SERVICE);
                            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                                startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 4242);
                                return;
                            }
                        }
                        Intent i = new Intent(Settings.ACTION_HOME_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent i2 = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i2);
                        } catch (Exception e2) {
                            Intent i3 = new Intent(Intent.ACTION_MAIN);
                            i3.addCategory(Intent.CATEGORY_HOME);
                            i3.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i3);
                        }
                    }
                }
            });
        }

        // Wi-Fi : état et activation (sur Android récent, ouvre le panneau système).
        @JavascriptInterface
        public String wifiState() {
            try {
                android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                return w != null && w.isWifiEnabled() ? "on" : "off";
            } catch (Exception e) {
                return "na";
            }
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void wifiSet(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT < 29) {
                            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                            w.setWifiEnabled(on);
                        } else {
                            Intent i = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        }
                    } catch (Exception e) {
                        try {
                            Intent i2 = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i2);
                        } catch (Exception e2) {
                            Toast.makeText(MainActivity.this, L("Réglages indisponibles", "Settings not available"), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        // Capture d'écran enregistrée dans Images > Cockpit.
        @JavascriptInterface
        public void screenshot() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final android.view.View root = getWindow().getDecorView();
                        final android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                        android.view.PixelCopy.request(getWindow(), bm, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                            @Override
                            public void onPixelCopyFinished(int r) {
                                boolean ok = false;
                                try {
                                    if (r == android.view.PixelCopy.SUCCESS) {
                                        android.content.ContentValues v = new android.content.ContentValues();
                                        v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Cockpit-" + System.currentTimeMillis() + ".png");
                                        v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                                        if (Build.VERSION.SDK_INT >= 29) {
                                            v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cockpit");
                                        }
                                        android.net.Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                                        java.io.OutputStream o = getContentResolver().openOutputStream(u);
                                        bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
                                        o.close();
                                        ok = true;
                                    }
                                } catch (Exception e) {
                                    ok = false;
                                }
                                fondJs("window.shotDone&&window.shotDone(" + ok + ")");
                            }
                        }, new android.os.Handler(android.os.Looper.getMainLooper()));
                    } catch (Exception e) {
                        fondJs("window.shotDone&&window.shotDone(false)");
                    }
                }
            });
        }

        // Marque, modèle et version d'Android du poste (pour l'aide).
        @JavascriptInterface
        public String deviceInfo() {
            return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
        }

        // Vrai si le poste a une connexion internet.
        @JavascriptInterface
        public boolean online() {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } catch (Exception e) {
                return true;
            }
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
