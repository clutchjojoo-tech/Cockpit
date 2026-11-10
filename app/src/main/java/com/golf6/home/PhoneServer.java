package com.golf6.home;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Locale;

/**
 * Mini serveur web : le téléphone (même Wi-Fi ou partage de connexion) ouvre
 * http://IP:8765/?t=JETON et personnalise Cockpit en direct.
 * Lecture seule pour les fichiers de l'appli ; toute modification passe par l'interface (phoneSet).
 */
class PhoneServer {

    interface Hote {
        String js(String code);          // exécute du JavaScript dans l'interface et renvoie le résultat (texte)
        byte[] apercu();                 // capture JPEG de l'écran de Cockpit (ou null)
        boolean photo(byte[] jpeg, String cible);  // enregistre une photo envoyée par le téléphone
        void connecte();                 // un téléphone vient de se connecter
    }

    static final int PORT = 8765;
    private final AssetManager assets;
    private final Hote hote;
    final String jeton;
    private ServerSocket serveur;
    private volatile boolean actif = false;
    private volatile long dernierContact = 0;

    PhoneServer(AssetManager a, Hote h, String jetonFixe) {
        assets = a;
        hote = h;
        if (jetonFixe != null && jetonFixe.matches("[a-z0-9]{8}")) {
            jeton = jetonFixe;
            return;
        }
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        jeton = sb.toString();
    }

    synchronized void demarrer() {
        if (actif) return;
        try {
            serveur = new ServerSocket(PORT);
            serveur.setReuseAddress(true);
        } catch (Exception e) {
            return;
        }
        actif = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (actif) {
                    try {
                        final Socket s = serveur.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                traiter(s);
                            }
                        }).start();
                    } catch (Exception e) {
                        if (!actif) return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    synchronized void arreter() {
        actif = false;
        try { if (serveur != null) serveur.close(); } catch (Exception e) { }
        serveur = null;
    }

    boolean telephoneRecent() {
        return System.currentTimeMillis() - dernierContact < 15000;
    }

    static String adresseIp() {
        String meilleure = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String nom = ni.getName().toLowerCase(Locale.ROOT);
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (nom.startsWith("wlan") || nom.startsWith("ap") || nom.startsWith("swlan")) return ip;
                    if (meilleure.isEmpty() && a.isSiteLocalAddress()) meilleure = ip;
                }
            }
        } catch (Exception e) { }
        return meilleure;
    }

    String url() {
        String ip = adresseIp();
        return ip.isEmpty() ? "" : "http://" + ip + ":" + PORT + "/?t=" + jeton;
    }

    // ---------- HTTP minimal ----------
    private void traiter(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            String ligne = lireLigne(in);
            if (ligne == null) { s.close(); return; }
            String[] p = ligne.split(" ");
            if (p.length < 2) { s.close(); return; }
            String methode = p[0], chemin = p[1];
            int longueur = 0;
            String l;
            while ((l = lireLigne(in)) != null && l.length() > 0) {
                int i = l.indexOf(':');
                if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("content-length")) {
                    try { longueur = Integer.parseInt(l.substring(i + 1).trim()); } catch (Exception e) { }
                }
            }
            byte[] corps = new byte[0];
            if (longueur > 0 && longueur < 12_000_000) {
                corps = new byte[longueur];
                int n = 0;
                while (n < longueur) {
                    int r = in.read(corps, n, longueur - n);
                    if (r < 0) break;
                    n += r;
                }
            }
            String requete = "", route = chemin;
            int q = chemin.indexOf('?');
            if (q >= 0) { route = chemin.substring(0, q); requete = chemin.substring(q + 1); }
            String t = param(requete, "t");
            OutputStream out = s.getOutputStream();

            if (route.equals("/") || route.equals("/index.html")) {
                envoyerAsset(out, "phone/index.html");
            } else if (route.equals("/manifest.json") || route.equals("/icon.png") || route.startsWith("/p/")) {
                envoyerAsset(out, route.equals("/manifest.json") ? "phone/manifest.json" : route.equals("/icon.png") ? "phone/icon.png" : "phone/" + route.substring(3));
            } else if (route.startsWith("/a/")) {
                envoyerAsset(out, route.substring(3));
            } else if (route.startsWith("/api/")) {
                if (!jeton.equals(t)) {
                    envoyer(out, 403, "application/json", "{\"err\":\"jeton\"}".getBytes("UTF-8"));
                } else {
                    boolean nouveau = !telephoneRecent();
                    dernierContact = System.currentTimeMillis();
                    if (nouveau) hote.connecte();
                    api(out, route, methode, corps, requete);
                }
            } else {
                envoyer(out, 404, "text/plain", "introuvable".getBytes("UTF-8"));
            }
            out.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception e2) { }
        }
    }

    private void api(OutputStream out, String route, String methode, byte[] corps, String requete) throws Exception {
        if (route.equals("/api/state")) {
            String r = hote.js("window.phoneCatalog?JSON.stringify(window.phoneCatalog()):'{}'");
            envoyer(out, 200, "application/json", (r == null ? "{}" : r).getBytes("UTF-8"));
        } else if (route.equals("/api/layout")) {
            String r = hote.js("window.phoneLayout?JSON.stringify(window.phoneLayout()):'[]'");
            envoyer(out, 200, "application/json", (r == null ? "[]" : r).getBytes("UTF-8"));
        } else if (route.equals("/api/set") && methode.equals("POST")) {
            String json = new String(corps, "UTF-8");
            // vérifie que c'est bien un objet JSON avant de le passer à l'interface
            new org.json.JSONObject(json);
            String r = hote.js("window.phoneSet?JSON.stringify(window.phoneSet(" + json + ")):'{}'");
            envoyer(out, 200, "application/json", (r == null ? "{}" : r).getBytes("UTF-8"));
        } else if (route.equals("/api/shot")) {
            byte[] img = hote.apercu();
            if (img == null) envoyer(out, 204, "text/plain", new byte[0]);
            else envoyer(out, 200, "image/jpeg", img);
        } else if (route.equals("/api/photo") && methode.equals("POST")) {
            String cible = param(requete, "w");
            if (!cible.matches("home|lock|prof")) cible = "home";
            boolean ok = corps.length > 1000 && hote.photo(corps, cible);
            envoyer(out, 200, "application/json", ("{\"ok\":" + ok + "}").getBytes("UTF-8"));
        } else {
            envoyer(out, 404, "application/json", "{}".getBytes("UTF-8"));
        }
    }

    private void envoyerAsset(OutputStream out, String chemin) throws Exception {
        if (chemin.contains("..") || chemin.startsWith("/") || !chemin.matches("[A-Za-z0-9_./-]+")) {
            envoyer(out, 404, "text/plain", new byte[0]);
            return;
        }
        String ext = chemin.substring(chemin.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String type;
        switch (ext) {
            case "html": type = "text/html; charset=utf-8"; break;
            case "js": type = "text/javascript; charset=utf-8"; break;
            case "css": type = "text/css"; break;
            case "json": type = "application/json"; break;
            case "webp": type = "image/webp"; break;
            case "png": type = "image/png"; break;
            case "jpg": case "jpeg": type = "image/jpeg"; break;
            case "svg": type = "image/svg+xml"; break;
            default: envoyer(out, 404, "text/plain", new byte[0]); return;
        }
        try {
            InputStream in = assets.open(chemin);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            envoyer(out, 200, type, bo.toByteArray(), type.startsWith("image/"));
        } catch (Exception e) {
            envoyer(out, 404, "text/plain", new byte[0]);
        }
    }

    private static void envoyer(OutputStream out, int code, String type, byte[] donnees) throws Exception {
        envoyer(out, code, type, donnees, false);
    }

    private static void envoyer(OutputStream out, int code, String type, byte[] donnees, boolean cache) throws Exception {
        String etat = code == 200 ? "OK" : code == 204 ? "No Content" : code == 403 ? "Forbidden" : "Not Found";
        String h = "HTTP/1.1 " + code + " " + etat + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + donnees.length
                + "\r\nCache-Control: " + (cache && code == 200 ? "max-age=86400" : "no-store")
                + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(donnees);
    }

    private static String param(String q, String nom) {
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(nom)) return kv.substring(i + 1);
        }
        return "";
    }

    private static String lireLigne(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8000) break;
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }
}
