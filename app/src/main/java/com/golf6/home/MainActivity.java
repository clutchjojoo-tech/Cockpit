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
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // 3.0 : la vidéo de démarrage joue avec son sans toucher l'écran
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                android.webkit.WebResourceResponse r = carIntercept(request);
                return r != null ? r : super.shouldInterceptRequest(view, request);
            }

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
        obdDemarrer();   // 3.0 : reconnexion automatique à la prise OBD choisie
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        sendLocation();
        if (code == 2 || code == 3) obdDemarrer();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try { web.evaluateJavascript("window.onBg&&onBg(false)", null); } catch (Exception e) { }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { web.evaluateJavascript("window.onBg&&onBg(true)", null); } catch (Exception e) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ecouteEcran();
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

    // Mise hors tension / remise du contact : l'écran du poste s'éteint puis se rallume.
    private BroadcastReceiver recepteurEcran = null;
    private long ecranEteintA = 0;

    private void ecouteEcran() {
        if (recepteurEcran != null) return;
        try {
            recepteurEcran = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if ("android.media.VOLUME_CHANGED_ACTION".equals(a)) {
                        int type = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                        if (type != AudioManager.STREAM_MUSIC) return;
                        int v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                        int old = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -2);
                        if (v < 0 || v == old) return;
                        int max = 30;
                        try { max = ((AudioManager) getSystemService(AUDIO_SERVICE)).getStreamMaxVolume(AudioManager.STREAM_MUSIC); } catch (Exception e) { }
                        fondJs("window.onVol&&window.onVol(" + v + "," + max + ")");
                        return;
                    }
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                        ecranEteintA = System.currentTimeMillis();
                    } else if (Intent.ACTION_SCREEN_ON.equals(a) && ecranEteintA > 0
                            && System.currentTimeMillis() - ecranEteintA > 20000) {
                        ecranEteintA = 0;
                        fondJs("window.onPowerOn&&window.onPowerOn()");
                        if (prefs.getBoolean("voice_on", true)) {
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    saluer();
                                }
                            }, 2500);
                        }
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction("android.media.VOLUME_CHANGED_ACTION");
            registerReceiver(recepteurEcran, f);
        } catch (Exception e) {
            recepteurEcran = null;
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
                    // Voix enregistrée (ElevenLabs) d'abord : ne dépend pas de la synthèse vocale.
                    if (jouerVoix(nomSalut())) {
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

    // ---- Voix enregistrées (ElevenLabs), dans assets/voix ----
    private MediaPlayer voixPlayer;

    boolean jouerVoix(String nom) {
        if (!prefs.getBoolean("voice_on", true) || !"fr".equals(langue())) return false;
        if (nom == null || !nom.matches("[a-z0-9_]{2,30}")) return false;
        try {
            android.content.res.AssetFileDescriptor fd = getAssets().openFd("voix/" + nom + ".mp3");
            if (voixPlayer != null) {
                try { voixPlayer.release(); } catch (Exception e) { }
            }
            voixPlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                voixPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            } else {
                voixPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            voixPlayer.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            voixPlayer.prepare();
            float vol = prefs.getInt("voice_vol", 90) / 100f;
            voixPlayer.setVolume(vol, vol);
            voixPlayer.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String nomSalut() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return (h >= 5 && h < 12) ? "bonjour" : ((h >= 12 && h < 18) ? "bonapresmidi" : "bonsoir");
    }

    private void saluer() {
        if (jouerVoix(nomSalut())) {
            return;
        }
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

    // ---------- Personnalisation depuis le téléphone ----------
    private PhoneServer tel;

    private synchronized PhoneServer telephone() {
        if (tel == null) {
            String jt = prefs.getString("phone_tok", "");
            tel = new PhoneServer(getAssets(), new PhoneServer.Hote() {
                @Override
                public String js(final String code) {
                    final String[] res = {null};
                    final java.util.concurrent.CountDownLatch fin = new java.util.concurrent.CountDownLatch(1);
                    web.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                web.evaluateJavascript(code, new android.webkit.ValueCallback<String>() {
                                    @Override
                                    public void onReceiveValue(String v) {
                                        try {
                                            Object o = new org.json.JSONTokener(v).nextValue();
                                            res[0] = o instanceof String ? (String) o : String.valueOf(o);
                                        } catch (Exception e) {
                                            res[0] = v;
                                        }
                                        fin.countDown();
                                    }
                                });
                            } catch (Exception e) {
                                fin.countDown();
                            }
                        }
                    });
                    try { fin.await(4, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { }
                    return res[0];
                }

                @Override
                public byte[] apercu() {
                    final byte[][] res = {null};
                    final java.util.concurrent.CountDownLatch fin = new java.util.concurrent.CountDownLatch(1);
                    web.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                int w = Math.max(1, web.getWidth() / 2), h = Math.max(1, web.getHeight() / 2);
                                Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                                Canvas c = new Canvas(bm);
                                c.scale(0.5f, 0.5f);
                                web.draw(c);
                                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                                bm.compress(Bitmap.CompressFormat.JPEG, 70, bo);
                                bm.recycle();
                                res[0] = bo.toByteArray();
                            } catch (Throwable e) {
                                res[0] = null;
                            }
                            fin.countDown();
                        }
                    });
                    try { fin.await(4, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { }
                    return res[0];
                }

                @Override
                public boolean photo(byte[] jpeg, String cible) {
                    try {
                        Bitmap full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                        if (full == null) return false;
                        int w = full.getWidth(), h = full.getHeight(), cw = w, ch = h;
                        if (w * 9 > h * 16) cw = h * 16 / 9; else ch = w * 9 / 16;
                        Bitmap crop = Bitmap.createBitmap(full, (w - cw) / 2, (h - ch) / 2, cw, ch);
                        Bitmap fin = Bitmap.createScaledBitmap(crop, 1280, 720, true);
                        SharedPreferences.Editor ed = prefs.edit();
                        if ("lock".equals(cible)) {
                            enregistrerFond(fin, "fond_lock.jpg", 1f, 1f, 1f);
                        } else if ("prof".equals(cible)) {
                            enregistrerFond(fin, "fond_prof.jpg", 1f, 1f, 1f);
                        } else {
                            enregistrerFond(fin, "fond_jour.jpg", 1f, 1f, 1f);
                            enregistrerFond(fin, "fond_on.jpg", 0.42f, 0.48f, 0.68f);
                            enregistrerFond(fin, "fond_off.jpg", 0.26f, 0.30f, 0.46f);
                            ed.putBoolean("bg_u_jour", true).putBoolean("bg_u_on", false).putBoolean("bg_u_off", false);
                        }
                        ed.putLong("bg_stamp", System.currentTimeMillis()).apply();
                        fondJs("prof".equals(cible) ? "window.profBgPhoto&&window.profBgPhoto()" : "window.bgDone&&window.bgDone(true)");
                        return true;
                    } catch (Throwable e) {
                        return false;
                    }
                }

                @Override
                public void connecte() {
                    fondJs("window.onPhone&&window.onPhone()");
                }
            }, jt);
            prefs.edit().putString("phone_tok", tel.jeton).apply();
        }
        return tel;
    }

    @Override
    protected void onDestroy() {
        obdArreter();
        if (tel != null) tel.arreter();
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    // ---------- Cockpit 3.0 : voitures 3D à télécharger (packs) ----------
    // Un pack = un zip publié une fois dans la Release « voitures » du dépôt GitHub. Il est décompressé
    // dans files/cars/<id>/ ; la page le lit comme s'il était dans m3d/<dossier>/ (voir shouldInterceptRequest).
    private static final String CARS_URL = "https://github.com/clutchjojoo-tech/Cockpit/releases/download/voitures/";
    private static final String[][] CARS_PACKS = {{"golf6", "golf6", "cockpit-golf6.zip"}, {"p207", "207", "cockpit-207.zip"}};   // id, dossier m3d/, fichier
    private final java.util.Map<String, Integer> carDl = new java.util.HashMap<>();

    private String[] carPack(String id) {
        for (String[] c : CARS_PACKS) if (c[0].equals(id)) return c;
        return null;
    }

    private File carDir(String id) {
        return new File(new File(getFilesDir(), "cars"), id);
    }

    private long dirSize(File d) {
        long n = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) n += f.isDirectory() ? dirSize(f) : f.length();
        return n;
    }

    private void carTelecharger(final String id) {
        final String[] c = carPack(id);
        if (c == null) return;
        synchronized (carDl) {
            if (carDl.containsKey(id)) return;
            carDl.put(id, 0);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                File zip = new File(getCacheDir(), id + ".zip.part");
                File tmp = new File(new File(getFilesDir(), "cars"), id + ".tmp");
                try {
                    supprimerDossier(tmp);
                    HttpURLConnection con = (HttpURLConnection) new URL(CARS_URL + c[2]).openConnection();
                    con.setInstanceFollowRedirects(true);
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(30000);
                    int code = con.getResponseCode();
                    if (code != 200) throw new Exception("HTTP " + code);
                    long total = con.getContentLengthLong(), lu = 0;
                    int last = -1;
                    InputStream in = con.getInputStream();
                    OutputStream out = new FileOutputStream(zip);
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        lu += n;
                        int pct = total > 0 ? (int) (lu * 90 / total) : 0;
                        if (pct != last) {
                            last = pct;
                            synchronized (carDl) { carDl.put(id, pct); }
                            fondJs("window.onCarDl&&window.onCarDl('" + id + "'," + pct + ",'dl')");
                        }
                    }
                    out.close();
                    in.close();
                    // décompression : noms simples seulement (pas de « .. »), dans un dossier temporaire
                    java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zip));
                    java.util.zip.ZipEntry e;
                    while ((e = zi.getNextEntry()) != null) {
                        String nom = e.getName();
                        if (!nom.matches("[A-Za-z0-9_./-]+") || nom.contains("..") || nom.startsWith("/")) throw new Exception("nom");
                        File f = new File(tmp, nom);
                        if (e.isDirectory()) { f.mkdirs(); continue; }
                        f.getParentFile().mkdirs();
                        OutputStream o = new FileOutputStream(f);
                        while ((n = zi.read(b)) > 0) o.write(b, 0, n);
                        o.close();
                    }
                    zi.close();
                    if (!new File(tmp, "car.json").exists() || !new File(tmp, "rot.mp4").exists()) throw new Exception("pack incomplet");
                    File dir = carDir(id);
                    supprimerDossier(dir);
                    if (!tmp.renameTo(dir)) throw new Exception("rename");
                    fondJs("window.onCarDl&&window.onCarDl('" + id + "',100,'ok')");
                } catch (Throwable t) {
                    supprimerDossier(tmp);
                    fondJs("window.onCarDl&&window.onCarDl('" + id + "',0,'err')");
                } finally {
                    zip.delete();
                    synchronized (carDl) { carDl.remove(id); }
                }
            }
        }, "pack-" + id).start();
    }

    /** Sert les fichiers d'un pack téléchargé à la place de file:///android_asset/m3d/<dossier>/… (avec les plages, pour la vidéo). */
    private android.webkit.WebResourceResponse carIntercept(android.webkit.WebResourceRequest req) {
        try {
            String u = req.getUrl().toString(), pre = "file:///android_asset/m3d/";
            if (!u.startsWith(pre)) return null;
            String rest = u.substring(pre.length());
            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);
            for (String[] c : CARS_PACKS) {
                if (!rest.startsWith(c[1] + "/")) continue;
                String rel = Uri.decode(rest.substring(c[1].length() + 1));
                if (rel.contains("..")) return null;
                final File f = new File(carDir(c[0]), rel);
                if (!f.isFile()) return null;
                String mime = rel.endsWith(".mp4") ? "video/mp4" : rel.endsWith(".webp") ? "image/webp"
                        : rel.endsWith(".json") ? "application/json" : "application/octet-stream";
                long len = f.length(), s = 0, e = len - 1;
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Accept-Ranges", "bytes");
                h.put("Access-Control-Allow-Origin", "*");
                String rg = req.getRequestHeaders() != null ? req.getRequestHeaders().get("Range") : null;
                if (rg == null && req.getRequestHeaders() != null) rg = req.getRequestHeaders().get("range");
                final java.io.FileInputStream in = new java.io.FileInputStream(f);
                if (rg != null && rg.startsWith("bytes=")) {
                    String[] p = rg.substring(6).split("-", -1);
                    if (p[0].length() > 0) s = Long.parseLong(p[0].trim());
                    if (p.length > 1 && p[1].trim().length() > 0) e = Math.min(len - 1, Long.parseLong(p[1].trim()));
                    if (s > e || s >= len) { in.close(); return null; }
                    long sk = s;
                    while (sk > 0) { long k = in.skip(sk); if (k <= 0) break; sk -= k; }
                    final long reste0 = e - s + 1;
                    InputStream lim = new InputStream() {
                        long reste = reste0;
                        @Override public int read() throws java.io.IOException { if (reste <= 0) return -1; int v = in.read(); if (v >= 0) reste--; return v; }
                        @Override public int read(byte[] b, int o, int n) throws java.io.IOException { if (reste <= 0) return -1; int r = in.read(b, o, (int) Math.min(n, reste)); if (r > 0) reste -= r; return r; }
                        @Override public void close() throws java.io.IOException { in.close(); }
                    };
                    h.put("Content-Range", "bytes " + s + "-" + e + "/" + len);
                    h.put("Content-Length", String.valueOf(reste0));
                    return new android.webkit.WebResourceResponse(mime, null, 206, "Partial Content", h, lim);
                }
                h.put("Content-Length", String.valueOf(len));
                return new android.webkit.WebResourceResponse(mime, null, 200, "OK", h, in);
            }
        } catch (Throwable t) {
            // fichier illisible : la page affichera la Golf
        }
        return null;
    }

    // ---------- Cockpit 3.0 : prise OBD Bluetooth (ELM327), lecture seule ----------
    // La prise est d'abord associée dans le Bluetooth d'Android, puis choisie dans Cockpit
    // (adresse gardée dans obd_addr). Cockpit s'y reconnecte tout seul. Rien n'est jamais écrit
    // dans la voiture : seules des demandes de lecture (mode 01, 03) et des commandes AT de la prise.
    private static final java.util.UUID OBD_SPP = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private volatile boolean obdRun = false;
    private volatile String obdEtat = "off";
    private volatile String obdNom = "";
    private volatile String obdSupp = "[]";
    private volatile String obdDtc = "[]";
    private volatile android.bluetooth.BluetoothSocket obdSock;
    private InputStream obdIn;
    private OutputStream obdOut;

    private void obdJs(final String code) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                try { web.evaluateJavascript(code, null); } catch (Exception e) { }
            }
        });
    }

    private String obdInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("etat", obdEtat);
            o.put("nom", obdNom);
            o.put("addr", prefs.getString("obd_addr", ""));
            o.put("supp", new JSONArray(obdSupp));
            o.put("dtc", new JSONArray(obdDtc));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void obdSetEtat(String e) {
        obdEtat = e;
        obdJs("window.onObdState&&window.onObdState(" + obdInfo() + ")");
    }

    private void obdDemarrer() {
        final String addr = prefs.getString("obd_addr", "");
        if (addr.length() == 0 || obdRun) return;
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            obdSetEtat("permission");
            try { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 3); } catch (Exception e) { }
            return;
        }
        obdRun = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                obdBoucle(addr);
            }
        }, "obd").start();
    }

    private void obdArreter() {
        obdRun = false;
        obdFermer();
        obdSetEtat("off");
    }

    private void obdFermer() {
        try { if (obdSock != null) obdSock.close(); } catch (Exception e) { }
        obdSock = null;
        obdIn = null;
        obdOut = null;
    }

    private void obdDormir(long ms) {
        long fin = System.currentTimeMillis() + ms;
        while (obdRun && System.currentTimeMillis() < fin) {
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
        }
    }

    /** Envoie une commande et lit jusqu'au « > » de la prise. Réponse nettoyée (sans espaces). */
    private String obdCmd(String c, long attente) throws Exception {
        if (obdOut == null || obdIn == null) throw new Exception("fermé");
        while (obdIn.available() > 0) obdIn.read();
        obdOut.write((c + "\r").getBytes("US-ASCII"));
        obdOut.flush();
        StringBuilder sb = new StringBuilder();
        long fin = System.currentTimeMillis() + attente;
        while (System.currentTimeMillis() < fin) {
            if (obdIn.available() > 0) {
                int b = obdIn.read();
                if (b < 0) throw new Exception("coupé");
                if (b == '>') break;
                sb.append((char) b);
            } else {
                Thread.sleep(15);
            }
        }
        return sb.toString().replace("SEARCHING...", "").replaceAll("[\\s\\x00]", "").toUpperCase(Locale.ROOT);
    }

    /** Octets de données qui suivent l'en-tête (ex. « 410C ») dans la réponse, ou null. */
    private int[] obdOctets(String r, String tete, int n) {
        int i = r.indexOf(tete);
        if (i < 0) return null;
        i += tete.length();
        if (r.length() < i + n * 2) return null;
        int[] o = new int[n];
        try {
            for (int k = 0; k < n; k++) o[k] = Integer.parseInt(r.substring(i + k * 2, i + k * 2 + 2), 16);
        } catch (Exception e) {
            return null;
        }
        return o;
    }

    private void obdBoucle(String addr) {
        // PID lus : régime, vitesse, liquide de refroidissement, admission, charge, essence, air extérieur, huile, débit d'essence
        final int[] PIDS = {0x0C, 0x0D, 0x05, 0x0F, 0x04, 0x2F, 0x46, 0x5C, 0x5E};
        while (obdRun) {
            try {
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                if (a == null || !a.isEnabled()) {
                    obdSetEtat("bt_off");
                    obdDormir(10000);
                    continue;
                }
                BluetoothDevice d = a.getRemoteDevice(addr);
                String n = null;
                try { n = d.getName(); } catch (Exception e) { }
                obdNom = n != null ? n : addr;
                obdSetEtat("connexion");
                try { a.cancelDiscovery(); } catch (Exception e) { }
                android.bluetooth.BluetoothSocket s;
                try {
                    s = d.createRfcommSocketToServiceRecord(OBD_SPP);
                    s.connect();
                } catch (Exception e1) {
                    s = d.createInsecureRfcommSocketToServiceRecord(OBD_SPP);
                    s.connect();
                }
                obdSock = s;
                obdIn = s.getInputStream();
                obdOut = s.getOutputStream();
                obdCmd("ATZ", 3000);
                obdCmd("ATE0", 1500);
                obdCmd("ATL0", 1500);
                obdCmd("ATS0", 1500);
                obdCmd("ATH0", 1500);
                obdCmd("ATSP0", 1500);
                obdSetEtat("voiture");
                String r = obdCmd("0100", 12000);   // la recherche du protocole peut être longue
                int[] b = obdOctets(r, "4100", 4);
                if (b == null) {
                    // contact coupé ou voiture qui ne répond pas : on réessaie plus tard
                    obdFermer();
                    obdSetEtat("pas_voiture");
                    obdDormir(15000);
                    continue;
                }
                boolean[] sup = new boolean[0x61];
                for (int base = 0; base <= 0x40; base += 0x20) {
                    if (base > 0) {
                        if (!sup[base]) break;
                        String rr = obdCmd(String.format(Locale.ROOT, "01%02X", base), 4000);
                        b = obdOctets(rr, String.format(Locale.ROOT, "41%02X", base), 4);
                        if (b == null) break;
                    }
                    long m = ((long) b[0] << 24) | ((long) b[1] << 16) | ((long) b[2] << 8) | b[3];
                    for (int k = 1; k <= 32; k++) sup[base + k] = ((m >> (32 - k)) & 1) == 1;
                }
                JSONArray js = new JSONArray();
                final List<Integer> lus = new ArrayList<>();
                for (int p : PIDS) if (p < sup.length && sup[p]) { lus.add(p); js.put(String.format(Locale.ROOT, "%02X", p)); }
                String v0 = obdCmd("ATRV", 1500);
                if (v0.matches(".*[0-9]+\\.?[0-9]*V.*")) js.put("RV");
                obdSupp = js.toString();
                obdLireDtc();
                obdSetEtat("ok");
                long dtcT = System.currentTimeMillis(), rvT = 0;
                while (obdRun) {
                    JSONObject o = new JSONObject();
                    double kmh = -1;
                    for (int p : lus) {
                        String h = String.format(Locale.ROOT, "%02X", p);
                        String rr = obdCmd("01" + h, 2500);
                        if (rr.contains("UNABLETOCONNECT") || rr.contains("CANERROR")) throw new Exception("voiture");
                        int nb = (p == 0x0C || p == 0x5E) ? 2 : 1;
                        int[] x = obdOctets(rr, "41" + h, nb);
                        if (x == null) continue;
                        switch (p) {
                            case 0x0C: o.put("rpm", (x[0] * 256 + x[1]) / 4); break;
                            case 0x0D: kmh = x[0]; o.put("kmh", x[0]); break;
                            case 0x05: o.put("cool", x[0] - 40); break;
                            case 0x0F: o.put("intake", x[0] - 40); break;
                            case 0x04: o.put("load", Math.round(x[0] * 100 / 255.0)); break;
                            case 0x2F: o.put("fuel", Math.round(x[0] * 1000 / 255.0) / 10.0); break;
                            case 0x46: o.put("amb", x[0] - 40); break;
                            case 0x5C: o.put("oil", x[0] - 40); break;
                            case 0x5E: {
                                double lh = (x[0] * 256 + x[1]) / 20.0;
                                o.put("lh", lh);
                                if (kmh >= 5) o.put("cons", Math.round(lh / kmh * 1000) / 10.0);
                                break;
                            }
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (now - rvT > 5000) {
                        rvT = now;
                        String rv = obdCmd("ATRV", 1500).replace("V", "");
                        try { double v = Double.parseDouble(rv); if (v > 5 && v < 20) o.put("volt", v); } catch (Exception e) { }
                    }
                    if (now - dtcT > 300000) { dtcT = now; obdLireDtc(); obdSetEtat("ok"); }
                    obdJs("window.onObd&&window.onObd(" + o.toString() + ")");
                    obdDormir(400);
                }
            } catch (Throwable e) {
                obdFermer();
                if (obdRun) {
                    obdSetEtat("erreur");
                    obdDormir(10000);
                }
            }
        }
        obdFermer();
    }

    /** Codes défaut enregistrés (mode 03), lecture seule. */
    private void obdLireDtc() {
        JSONArray out = new JSONArray();
        try {
            String r = obdCmd("03", 5000);
            // réponses CAN sur plusieurs lignes « 0:… 1:… » : on retire les numéros de ligne
            r = r.replaceAll("[0-9A-F]:", "");
            int i = r.indexOf("43");
            if (i >= 0) {
                String d = r.substring(i + 2).replaceAll("[^0-9A-F]", "");
                if ((d.length() / 2) % 2 == 1) d = d.substring(2);   // octet « nombre de codes » (CAN)
                String L = "PCBU";
                for (int k = 0; k + 4 <= d.length(); k += 4) {
                    String c = d.substring(k, k + 4);
                    if (c.equals("0000")) continue;
                    int a = Integer.parseInt(c.substring(0, 1), 16);
                    String code = L.charAt(a >> 2) + Integer.toString(a & 3) + c.substring(1);
                    out.put(code);
                }
            }
        } catch (Exception e) {
            // pas de codes lisibles
        }
        obdDtc = out.toString();
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Touche Accueil du poste (Cockpit est l'écran d'accueil) : retour à l'accueil de Cockpit.
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME) && web != null) {
            try { web.evaluateJavascript("window.goHome && window.goHome()", null); } catch (Exception e) { }
        }
    }

    private long dernierRetour = 0;

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        // Touche Retour (écran, volant ou façade) : toujours gérée par Cockpit.
        if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (ev.getAction() == android.view.KeyEvent.ACTION_UP && System.currentTimeMillis() - dernierRetour > 250) {
                dernierRetour = System.currentTimeMillis();
                try { web.evaluateJavascript("window.goBack && window.goBack()", null); } catch (Exception e) { }
            }
            return true;
        }
        // Touches volume (façade ou volant) quand Cockpit est affiché : on règle le son nous-mêmes,
        // sans demander à Android d'afficher son propre panneau ; Cockpit affiche le sien.
        int kc = ev.getKeyCode();
        if (kc == android.view.KeyEvent.KEYCODE_VOLUME_UP || kc == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                try {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    int dir = kc == android.view.KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                            : kc == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER
                            : AudioManager.ADJUST_TOGGLE_MUTE;
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
                    int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    web.evaluateJavascript("window.onVol&&window.onVol(" + v + "," + max + ")", null);
                } catch (Exception e) {
                    return super.dispatchKeyEvent(ev);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
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

    // ---------- Cockpit 3.0 : tracés des trajets (historique en 3D) ----------
    // Un point tous les 25 m environ, en roulant. Fichier en cours : trajets/cur.txt ; à l'archivage du trajet
    // par la page, il devient trajets/<id>.txt. Les données restent sur le poste. 60 tracés gardés au plus.
    private static final int TRACE_GARDER = 60;
    private double trLat = 999, trLon = 999;
    private int trPts = 0;

    private File dossierTrajets() {
        File d = new File(getFilesDir(), "trajets");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private synchronized void enregistrerPoint(Location l, float kmh, double alt) {
        try {
            if (!prefs.getBoolean("tr_trace", true) || kmh < 3f) return;
            double la = l.getLatitude(), lo = l.getLongitude();
            if (trLat != 999) {
                float[] d = new float[1];
                Location.distanceBetween(trLat, trLon, la, lo, d);
                if (d[0] < 25f) return;
            }
            if (trPts >= 20000) return;   // garde-fou : environ 500 km
            trLat = la; trLon = lo; trPts++;
            String ligne = String.format(Locale.US, "%.6f,%.6f,%.0f,%.0f,%d\n", la, lo, alt, kmh, System.currentTimeMillis() / 1000);
            FileOutputStream fo = new FileOutputStream(new File(dossierTrajets(), "cur.txt"), true);
            try { fo.write(ligne.getBytes("UTF-8")); } finally { fo.close(); }
        } catch (Throwable e) { /* un point perdu n'empêche rien */ }
    }

    private void nettoyerTrajets() {
        File[] l = dossierTrajets().listFiles();
        if (l == null) return;
        java.util.Arrays.sort(l, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        int n = 0;
        for (File f : l) {
            if (f.getName().equals("cur.txt")) continue;
            if (++n > TRACE_GARDER) f.delete();
        }
    }

    // ---------- Cockpit 3.0 — phase 2 : outils de sauvegarde complète ----------

    private static final String[] PHOTOS_SAUV = {"fond_jour.jpg", "fond_on.jpg", "fond_off.jpg", "fond_lock.jpg", "fond_prof.jpg"};
    private static final int SAUV_GARDER = 3;

    private File dossierSauvegardes() {
        return new File(getFilesDir(), "sauvegardes");
    }

    private synchronized String sauvegardeComplete(String localJson) {
        try {
            if (localJson == null) return "";
            // identifiant de longueur fixe, toujours plus grand que les précédents : l'ordre alphabétique
            // est l'ordre chronologique, même si l'horloge du poste recule ou si deux sauvegardes tombent la même seconde
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
            String max = "";
            File[] ex = dossierSauvegardes().listFiles();
            if (ex != null) for (File f : ex) if (f.getName().compareTo(max) > 0) max = f.getName();
            long t = System.currentTimeMillis();
            String id = fmt.format(new java.util.Date(t));
            if (id.compareTo(max) <= 0) {
                try { t = fmt.parse(max.substring(0, 19)).getTime(); } catch (Throwable e) { /* nom ancien : on garde t */ }
                do { id = fmt.format(new java.util.Date(++t)); } while (id.compareTo(max) <= 0);
            }
            File dir = new File(dossierSauvegardes(), id);
            if (!dir.mkdirs()) return "";
            ecrireOctets(new File(dir, "local.json"), localJson.getBytes("UTF-8"));
            ecrireOctets(new File(dir, "prefs.json"), prefsEnJson().getBytes("UTF-8"));
            JSONArray ph = new JSONArray();
            for (String p : PHOTOS_SAUV) {
                File src = new File(getFilesDir(), p);
                if (src.isFile()) {
                    ecrireOctets(new File(dir, p), lireOctets(src));
                    ph.put(p + ":" + src.length());
                }
            }
            File[] trs = dossierTrajets().listFiles();
            if (trs != null && trs.length > 0) {
                File dt = new File(dir, "trajets");
                dt.mkdirs();
                for (File tf : trs) if (tf.isFile()) ecrireOctets(new File(dt, tf.getName()), lireOctets(tf));
            }
            // relecture de contrôle avant de déclarer la sauvegarde complète
            if (!new String(lireOctets(new File(dir, "local.json")), "UTF-8").equals(localJson)) return "";
            JSONObject man = new JSONObject();
            man.put("id", dir.getName()).put("date", System.currentTimeMillis())
                    .put("local", localJson.length()).put("prefs", prefs.getAll().size()).put("photos", ph);
            ecrireOctets(new File(dir, "manifest.json"), man.toString().getBytes("UTF-8"));   // en DERNIER
            nettoyerSauvegardes();
            return dir.getName();
        } catch (Throwable e) {
            return "";
        }
    }

    private synchronized String restaurationComplete(String id) {
        try {
            if (id == null || !id.matches("[0-9A-Za-z-]{1,40}")) return null;
            File dir = new File(dossierSauvegardes(), id);
            if (!new File(dir, "manifest.json").isFile()) return null;   // sauvegarde incomplète : on n'y touche pas
            String local = new String(lireOctets(new File(dir, "local.json")), "UTF-8");
            String pj = new String(lireOctets(new File(dir, "prefs.json")), "UTF-8");
            new JSONObject(pj);   // lisible avant de toucher à quoi que ce soit
            if (!prefsDepuisJson(pj, true)) return null;
            for (String p : PHOTOS_SAUV) {
                File s = new File(dir, p), d = new File(getFilesDir(), p);
                if (s.isFile()) ecrireOctets(d, lireOctets(s));
                else if (d.isFile()) d.delete();   // la photo n'existait pas au moment de la sauvegarde
            }
            File st = new File(dir, "trajets");
            File[] trs = st.listFiles();
            if (trs != null) for (File tf : trs) if (tf.isFile()) ecrireOctets(new File(dossierTrajets(), tf.getName()), lireOctets(tf));
            return local;
        } catch (Throwable e) {
            return null;
        }
    }

    private String listerSauvegardes() {
        JSONArray a = new JSONArray();
        File[] l = dossierSauvegardes().listFiles();
        if (l == null) return "[]";
        java.util.Arrays.sort(l);
        for (int i = l.length - 1; i >= 0; i--) {
            try {
                File m = new File(l[i], "manifest.json");
                JSONObject o = m.isFile() ? new JSONObject(new String(lireOctets(m), "UTF-8")) : new JSONObject().put("id", l[i].getName());
                o.put("ok", m.isFile());
                a.put(o);
            } catch (Throwable e) { /* dossier abîmé : ignoré dans la liste, gardé sur le disque */ }
        }
        return a.toString();
    }

    /** Garde les SAUV_GARDER plus récentes sauvegardes complètes ; les incomplètes plus anciennes sont retirées. */
    private void nettoyerSauvegardes() {
        File[] l = dossierSauvegardes().listFiles();
        if (l == null) return;
        java.util.Arrays.sort(l);
        int completes = 0;
        for (int i = l.length - 1; i >= 0; i--) {
            boolean ok = new File(l[i], "manifest.json").isFile();
            if (ok && ++completes <= SAUV_GARDER) continue;
            if (completes >= 1) supprimerDossier(l[i]);   // jamais avant d'avoir au moins une sauvegarde complète plus récente
        }
    }

    private void supprimerDossier(File d) {
        File[] l = d.listFiles();
        if (l != null) for (File f : l) { if (f.isDirectory()) supprimerDossier(f); else f.delete(); }
        d.delete();
    }

    /** Préférences Android en JSON, avec leurs types (__t) pour les remettre à l'identique. */
    private String prefsEnJson() {
        try {
            JSONObject o = new JSONObject(), t = new JSONObject();
            for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                Object v = e.getValue();
                String k = e.getKey();
                if (v instanceof Boolean) { o.put(k, v); t.put(k, "b"); }
                else if (v instanceof Integer) { o.put(k, v); t.put(k, "i"); }
                else if (v instanceof Long) { o.put(k, v); t.put(k, "l"); }
                else if (v instanceof Float) { o.put(k, ((Float) v).doubleValue()); t.put(k, "f"); }
                else if (v instanceof Set) { o.put(k, new JSONArray((Set<?>) v)); t.put(k, "set"); }
                else if (v != null) { o.put(k, String.valueOf(v)); t.put(k, "s"); }
            }
            o.put("__t", t);
            return o.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    /** exact = true : état identique à la sauvegarde (les clés absentes sont retirées). Sinon : fusion. */
    private boolean prefsDepuisJson(String json, boolean exact) {
        try {
            JSONObject o = new JSONObject(json), t = o.optJSONObject("__t");
            SharedPreferences.Editor ed = prefs.edit();
            if (exact) ed.clear();
            java.util.Map<String, ?> avant = prefs.getAll();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("__t".equals(k)) continue;
                Object v = o.get(k);
                String ty = t != null ? t.optString(k, "") : "";
                if (ty.isEmpty()) {   // fichier sans types : on reprend le type actuel, sinon on le déduit
                    Object cur = avant.get(k);
                    ty = cur instanceof Boolean ? "b" : cur instanceof Integer ? "i" : cur instanceof Long ? "l"
                            : cur instanceof Float ? "f" : v instanceof Boolean ? "b" : v instanceof Integer ? "i"
                            : v instanceof Long ? "l" : v instanceof Number ? "f" : "s";
                }
                if ("b".equals(ty)) ed.putBoolean(k, v instanceof Boolean ? (Boolean) v : "true".equals(String.valueOf(v)));
                else if ("i".equals(ty)) ed.putInt(k, (int) Double.parseDouble(String.valueOf(v)));
                else if ("l".equals(ty)) ed.putLong(k, (long) Double.parseDouble(String.valueOf(v)));
                else if ("f".equals(ty)) ed.putFloat(k, (float) Double.parseDouble(String.valueOf(v)));
                else if ("set".equals(ty)) {
                    Set<String> s = new HashSet<String>();
                    JSONArray a = o.getJSONArray(k);
                    for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                    ed.putStringSet(k, s);
                } else ed.putString(k, String.valueOf(v));
            }
            return ed.commit();   // écriture immédiate et vérifiée (pas apply)
        } catch (Throwable e) {
            return false;
        }
    }

    private byte[] lireOctets(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Écrit dans un fichier temporaire puis le renomme : jamais de fichier à moitié écrit. */
    private void ecrireOctets(File f, byte[] b) throws Exception {
        File tmp = new File(f.getPath() + ".part");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(b);
            fo.getFD().sync();
        } finally {
            fo.close();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new Exception("renommage impossible : " + f.getName());
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
                        // Cockpit 3.0 : plus de coupure silencieuse à 300 000 octets.
                        // Au-delà de 40 Mo le fichier est refusé avec un message, jamais tronqué.
                        boolean tropGros = false;
                        while ((n = in.read(b)) > 0) {
                            if (bo.size() + n > 40 * 1024 * 1024) { tropGros = true; break; }
                            bo.write(b, 0, n);
                        }
                        in.close();
                        if (tropGros) {
                            fondJs("window.importDone&&window.importDone(null,'trop gros')");
                            return;
                        }
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
                    enregistrerPoint(l, v, alt);   // 3.0 : tracé du trajet (sur le poste seulement)
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

    private String appsEnvoyees = null;

    private void envoyerAppsJs(String json) {
        final String js = "window.setApps && window.setApps(" + json + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private void envoyerApps() {
        // 1) liste gardée en mémoire du poste : affichage instantané
        try {
            File cf = new File(getFilesDir(), "apps_cache.json");
            if (appsEnvoyees == null && cf.exists() && cf.length() > 10) {
                java.io.FileInputStream in = new java.io.FileInputStream(cf);
                byte[] buf = new byte[(int) cf.length()];
                int lu = 0;
                while (lu < buf.length) { int r = in.read(buf, lu, buf.length - lu); if (r < 0) break; lu += r; }
                in.close();
                appsEnvoyees = new String(buf, 0, lu, "UTF-8");
            }
            if (appsEnvoyees != null) envoyerAppsJs(appsEnvoyees);
        } catch (Exception e) { }
        // 2) liste fraîche en arrière-plan, renvoyée seulement si elle a changé
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
                    String json = arr.toString();
                    if (!json.equals(appsEnvoyees)) {
                        appsEnvoyees = json;
                        envoyerAppsJs(json);
                        try {
                            FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "apps_cache.json"));
                            out.write(json.getBytes("UTF-8"));
                            out.close();
                        } catch (Exception e2) { }
                    }
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

    // ---------- Postes HCN (appli CarCAN) : infos partagées par CarCAN ----------
    static final String[] HCN_ACTIONS = {"hcn.air.outdoor_temp", "android.mcu.device.action.REVSTATUS", "android.mcu.device.action.ACC"};
    private static java.lang.reflect.Method spGet;
    private static volatile boolean spKo = false;
    private java.util.Map<String, String> cacheProps = new java.util.HashMap<String, String>();
    private long cachePropsAt = 0;

    // Secours si Android refuse la lecture directe : relit « getprop » au plus toutes les 1,5 s.
    private synchronized String propSecours(String k) {
        if (System.currentTimeMillis() - cachePropsAt > 1500) {
            java.util.Map<String, String> m = new java.util.HashMap<String, String>();
            try {
                Process pr = Runtime.getRuntime().exec("getprop");
                BufferedReader br = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                String l;
                while ((l = br.readLine()) != null) {
                    int a = l.indexOf("]: [");
                    if (l.startsWith("[") && a > 0) m.put(l.substring(1, a), l.substring(a + 4, l.length() - (l.endsWith("]") ? 1 : 0)));
                }
                br.close();
            } catch (Exception e) { }
            cacheProps = m;
            cachePropsAt = System.currentTimeMillis();
        }
        String v = cacheProps.get(k);
        return v == null || v.length() == 0 ? null : v;
    }

    static String lireProp(String k) {
        try {
            if (spGet == null) spGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            String v = (String) spGet.invoke(null, k, "");
            return v == null || v.length() == 0 ? null : v;
        } catch (Throwable e) {
            spKo = true;
            return null;
        }
    }

    // Lit une seule clé, sans tout relire (rapide).
    private String lireCle(String k) {
        try {
            if (k.startsWith("bc ")) return etatBc.get(k);
            if (k.startsWith("prop ")) {
                String v = spKo ? null : lireProp(k.substring(5));
                return v != null ? v : propSecours(k.substring(5));
            }
            if (k.startsWith("system ")) return Settings.System.getString(getContentResolver(), k.substring(7));
            if (k.startsWith("global ")) return Settings.Global.getString(getContentResolver(), k.substring(7));
            if (k.startsWith("secure ")) return Settings.Secure.getString(getContentResolver(), k.substring(7));
        } catch (Throwable e) { }
        return null;
    }

    private boolean posteHcn() {
        try {
            getPackageManager().getPackageInfo("com.hcn.autocan", 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
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
        if (recepteurDetect != null) return;
        for (String a : HCN_ACTIONS) if (!actionsCan.contains(a)) actionsCan.add(a);
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
            if ("lock".equals(which)) return lireFondBase64("fond_lock.jpg");
            if ("prof".equals(which)) return lireFondBase64("fond_prof.jpg");
            return "";
        }

        @JavascriptInterface
        public void useLibBg(final String nom, final String cible) {
            if (nom == null || !nom.matches("fond_lib[0-9]+\\.webp")) {
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = getAssets().open(nom);
                        Bitmap fin = BitmapFactory.decodeStream(in);
                        in.close();
                        if (fin == null) {
                            throw new Exception("image");
                        }
                        SharedPreferences.Editor ed = prefs.edit();
                        if ("lock".equals(cible)) {
                            enregistrerFond(fin, "fond_lock.jpg", 1f, 1f, 1f);
                        } else {
                            enregistrerFond(fin, "fond_jour.jpg", 1f, 1f, 1f);
                            enregistrerFond(fin, "fond_on.jpg", 1f, 1f, 1f);
                            enregistrerFond(fin, "fond_off.jpg", 0.9f, 0.9f, 0.92f);
                            ed.putBoolean("bg_u_jour", true).putBoolean("bg_u_on", false).putBoolean("bg_u_off", false);
                        }
                        ed.putLong("bg_stamp", System.currentTimeMillis()).apply();
                        fondJs("window.bgDone&&window.bgDone(true)");
                    } catch (Throwable e) {
                        fondJs("window.bgDone&&window.bgDone(false)");
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void resetBackground() {
            new File(getFilesDir(), "fond_jour.jpg").delete();
            new File(getFilesDir(), "fond_on.jpg").delete();
            new File(getFilesDir(), "fond_off.jpg").delete();
            new File(getFilesDir(), "fond_lock.jpg").delete();
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
                        i.putExtra(Intent.EXTRA_TITLE, "cockpit-sauvegarde-" + new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()) + ".json");
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

        // ---------- Cockpit 3.0 : tracés des trajets ----------
        @JavascriptInterface
        public void traceEnable(boolean on) {
            prefs.edit().putBoolean("tr_trace", on).apply();
        }

        /** Le trajet en cours est archivé par la page : son tracé prend le même identifiant. */
        @JavascriptInterface
        public boolean traceSave(String id) {
            synchronized (MainActivity.this) {
                try {
                    if (id == null || !id.matches("[0-9]{1,15}")) return false;
                    File cur = new File(dossierTrajets(), "cur.txt");
                    trLat = 999; trLon = 999; trPts = 0;
                    if (!cur.isFile() || cur.length() == 0) return false;
                    boolean ok = cur.renameTo(new File(dossierTrajets(), id + ".txt"));
                    nettoyerTrajets();
                    return ok;
                } catch (Throwable e) { return false; }
            }
        }

        /** Remise à zéro du compteur de trajet sans archivage : on repart d'un tracé vide. */
        @JavascriptInterface
        public void traceReset() {
            synchronized (MainActivity.this) {
                new File(dossierTrajets(), "cur.txt").delete();
                trLat = 999; trLon = 999; trPts = 0;
            }
        }

        @JavascriptInterface
        public String traceGet(String id) {
            try {
                if (id == null || !id.matches("[0-9]{1,15}|cur")) return "";
                File f = new File(dossierTrajets(), id + ".txt");
                return f.isFile() ? new String(lireOctets(f), "UTF-8") : "";
            } catch (Throwable e) { return ""; }
        }

        @JavascriptInterface
        public String traceList() {
            JSONArray a = new JSONArray();
            File[] l = dossierTrajets().listFiles();
            if (l != null) for (File f : l) { String n = f.getName(); if (n.endsWith(".txt") && !n.equals("cur.txt")) a.put(n.substring(0, n.length() - 4)); }
            return a.toString();
        }

        @JavascriptInterface
        public void traceDelete(String id) {
            if (id != null && id.matches("[0-9]{1,15}")) new File(dossierTrajets(), id + ".txt").delete();
        }

        // ---------- Cockpit 3.0 — phase 2 : sauvegarde complète (page + préférences + photos) ----------

        /** Copie complète dans files/sauvegardes/<id>/ ; renvoie l'identifiant, "" si échec. */
        @JavascriptInterface
        public String backupFull(String localJson) {
            return sauvegardeComplete(localJson);
        }

        /** Remet préférences et photos d'une sauvegarde, renvoie le stockage de la page (null si échec). */
        @JavascriptInterface
        public String restoreFull(String id) {
            return restaurationComplete(id);
        }

        @JavascriptInterface
        public String listBackups() {
            return listerSauvegardes();
        }

        @JavascriptInterface
        public String getAllPrefs() {
            return prefsEnJson();
        }

        @JavascriptInterface
        public boolean setAllPrefs(String json) {
            return prefsDepuisJson(json, false);
        }

        @JavascriptInterface
        public String photoList() {
            JSONArray a = new JSONArray();
            for (String p : PHOTOS_SAUV) {
                File f = new File(getFilesDir(), p);
                if (f.isFile()) a.put(p);
            }
            return a.toString();
        }

        /** Photos de fond en base64 pour le fichier d'export : {"fond_jour.jpg":"…"}. */
        @JavascriptInterface
        public String photosBase64() {
            JSONObject o = new JSONObject();
            for (String p : PHOTOS_SAUV) {
                try {
                    File f = new File(getFilesDir(), p);
                    if (f.isFile()) o.put(p, android.util.Base64.encodeToString(lireOctets(f), android.util.Base64.NO_WRAP));
                } catch (Throwable e) { /* photo illisible : signalée par son absence */ }
            }
            return o.toString();
        }

        /** Remet une photo de fond venant d'un fichier d'export (noms autorisés uniquement). */
        @JavascriptInterface
        public boolean putPhotoBase64(String nom, String b64) {
            if (!java.util.Arrays.asList(PHOTOS_SAUV).contains(nom)) return false;
            try {
                byte[] b = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (BitmapFactory.decodeByteArray(b, 0, b.length) == null) return false;   // pas une image : on ne remplace rien
                File tmp = new File(getFilesDir(), nom + ".tmp");
                ecrireOctets(tmp, b);
                return tmp.renameTo(new File(getFilesDir(), nom));
            } catch (Throwable e) { return false; }
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
                    if (jouerVoix(nomSalut())) {
                        return;
                    }
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

        // ---------- Cockpit 3.0 : voitures à télécharger ----------
        @JavascriptInterface
        public String carStatus(String id) {
            try {
                JSONObject o = new JSONObject();
                String[] c = carPack(id);
                o.put("pack", c != null);
                File d = carDir(id);
                boolean ok = c != null && new File(d, "car.json").exists() && new File(d, "rot.mp4").exists();
                o.put("installed", ok);
                o.put("bytes", ok ? dirSize(d) : 0);
                Integer p;
                synchronized (carDl) { p = carDl.get(id); }
                o.put("dl", p != null);
                o.put("pct", p != null ? p : 0);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void carDownload(String id) {
            if (id != null && carPack(id) != null) carTelecharger(id);
        }

        @JavascriptInterface
        public void carDelete(String id) {
            if (id != null && carPack(id) != null) supprimerDossier(carDir(id));
        }

        // ---------- Cockpit 3.0 : prise OBD Bluetooth ----------
        @JavascriptInterface
        public String obdDevices() {
            JSONArray arr = new JSONArray();
            try {
                BluetoothAdapter a = btAdapter();
                if (a != null && btPermissionOk()) {
                    for (BluetoothDevice d : a.getBondedDevices()) {
                        JSONObject o = new JSONObject();
                        String n = d.getName();
                        o.put("nom", n != null ? n : d.getAddress());
                        o.put("addr", d.getAddress());
                        arr.put(o);
                    }
                }
            } catch (Exception e) {
                // liste vide
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void obdUse(String addr) {
            if (addr == null || !addr.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) return;
            obdArreter();
            prefs.edit().putString("obd_addr", addr.toUpperCase(Locale.ROOT)).apply();
            obdDemarrer();
        }

        @JavascriptInterface
        public void obdForget() {
            obdArreter();
            prefs.edit().remove("obd_addr").apply();
            obdSupp = "[]";
            obdDtc = "[]";
            obdNom = "";
            obdSetEtat("off");
        }

        @JavascriptInterface
        public String obdState() {
            return obdInfo();
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
                        for (String k : cles) {
                            String v = lireCle(k);
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
                        try { Thread.sleep(350); } catch (Exception e) { }
                    }
                }
            }).start();
        }

        // Poste avec CarCAN : ce que Cockpit arrive à lire (diagnostic + réglage automatique).
        // Démarre le mini serveur pour le téléphone et renvoie son adresse (vide si pas de réseau).
        @JavascriptInterface
        public String phoneUrl() {
            PhoneServer p = telephone();
            p.demarrer();
            return p.url();
        }

        @JavascriptInterface
        public boolean phoneConnected() {
            return tel != null && tel.telephoneRecent();
        }

        // Joue une voix enregistrée ; sinon la voix de synthèse lit le texte.
        @JavascriptInterface
        public void playVoice(final String nom, final String texte) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!jouerVoix(nom) && texte != null && texte.length() > 0) parler(texte);
                }
            });
        }

        @JavascriptInterface
        public String hcnInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("hcn", posteHcn());
                o.put("can", String.valueOf(Settings.System.getString(getContentResolver(), "hcnhw/can_type_settings")));
                o.put("ver", String.valueOf(Settings.System.getString(getContentResolver(), "hcn_can_version")));
                o.put("spd", String.valueOf(Settings.System.getString(getContentResolver(), "car_speed_value")));
                JSONObject p = new JSONObject();
                String[] ks = {"cardoor1", "cardoor2", "cardoor3", "cardoor4", "cardoor5", "radar0", "radar1", "radar2", "radar3",
                        "radar4", "radar5", "radar6", "radar7", "speed", "light0", "light1", "light2", "angle"};
                for (String k : ks) p.put(k, String.valueOf(lireCle("prop persist.vendor.cem." + k)));
                o.put("p", p);
                JSONObject b = new JSONObject();
                for (String a : HCN_ACTIONS) for (java.util.Map.Entry<String, String> e : etatBc.entrySet())
                    if (e.getKey().startsWith("bc " + a)) b.put(e.getKey().substring(3), e.getValue());
                o.put("bc", b);
            } catch (Exception e) { }
            return o.toString();
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
                        boolean fait = false;
                        try {
                            // Beaucoup de postes autorisent encore l'interrupteur direct.
                            android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                            fait = w.setWifiEnabled(on) && w.isWifiEnabled() == on;
                            if (!fait) {
                                Thread.sleep(300);
                                fait = w.isWifiEnabled() == on;
                            }
                        } catch (Throwable t) { fait = false; }
                        if (!fait) {
                            Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            if (i.resolveActivity(getPackageManager()) == null) {
                                i = new Intent(Settings.Panel.ACTION_WIFI);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            }
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
            if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try { requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 77); } catch (Exception e) { }
                    }
                });
                fondJs("window.shotDone&&window.shotDone(false,'perm')");
                return;
            }
            shotErr = "";
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
                                if (r == android.view.PixelCopy.SUCCESS) ok = saveShot(bm);
                                if (!ok) ok = screenshotView();
                                fondJs("window.shotDone&&window.shotDone(" + ok + ",'" + shotErr.replace("'", " ") + "')");
                            }
                        }, new android.os.Handler(android.os.Looper.getMainLooper()));
                    } catch (Exception e) {
                        boolean ok = screenshotView();
                        fondJs("window.shotDone&&window.shotDone(" + ok + ",'" + shotErr.replace("'", " ") + "')");
                    }
                }
            });
        }

        // Plan B : dessine la vue directement (marche quand PixelCopy est refusé par le poste).
        private boolean screenshotView() {
            try {
                android.view.View root = getWindow().getDecorView();
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(bm));
                return saveShot(bm);
            } catch (Exception e) {
                return false;
            }
        }

        private String shotErr = "";

        private boolean saveShot(android.graphics.Bitmap bm) {
            try {
                if (Build.VERSION.SDK_INT < 29) {
                    File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Cockpit");
                    dir.mkdirs();
                    File f = new File(dir, "Cockpit-" + System.currentTimeMillis() + ".png");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo);
                    fo.close();
                    android.media.MediaScannerConnection.scanFile(MainActivity.this, new String[]{f.getAbsolutePath()}, new String[]{"image/png"}, null);
                    return true;
                }
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Cockpit-" + System.currentTimeMillis() + ".png");
                v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= 29) {
                    v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cockpit");
                }
                android.net.Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) { shotErr = "galerie refusée"; return false; }
                java.io.OutputStream o = getContentResolver().openOutputStream(u);
                bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
                o.close();
                return true;
            } catch (Throwable e) {
                shotErr = e.getClass().getSimpleName();
                return false;
            }
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
