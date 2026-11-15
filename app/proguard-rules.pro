# Cockpit 3.2 : règles R8 pour la version release
# Le pont JavaScript (window.Android) doit garder ses noms de méthodes.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class com.golf6.home.MainActivity$Bridge { *; }
# Serveur du téléphone compagnon : interface appelée par réflexion de classe interne
-keep class com.golf6.home.PhoneServer { *; }
-keep interface com.golf6.home.PhoneServer$* { *; }
