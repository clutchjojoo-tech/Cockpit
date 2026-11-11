# Accueil Golf 6 pour poste Android

Ce dossier contient ton accueil sombre style constructeur, avec tes photos de la Golf 6 (fond coucher de soleil, démarrage animé, page Véhicule), pour ton poste Essgoo (Android 14, écran 1024×600).

## Ce qu'il y a dans l'app

- **Écran de départ** : ta photo coucher de soleil, l'heure, la météo (avec internet), « Faites glisser pour démarrer » et le bouton **feux**.
- **Menu (à gauche)** : logo Volkswagen en haut (il ramène à l'écran de départ), puis Accueil, Navigation (CarPlay), Musique, Téléphone, Menu, Réglages.
- **Accueil** : une **grosse fenêtre CarPlay** (ouvre ZLink, donc Waze et la musique de ton iPhone), ta carte Golf 6 et une barre d'applications avec les vraies icônes des applis installées.
- **Feux** : le bouton allume ou éteint les feux **sur la photo seulement**, jamais les vrais feux. Il est sur l'écran de départ, dans la carte Véhicule et dans Écran.
- **Menu > Véhicule** : ta Golf 6 et les informations. Rien n'est inventé : sans donnée, c'est écrit « Indisponible ».
- **Menu > Écran** : **luminosité**, feux, mode jour/nuit, animations.
- **Menu > Bluetooth** : état, appareils associés, bouton pour ouvrir les réglages Bluetooth du poste.
- **Menu > Audio** : volume, message vocal (activer, volume, test).
- **Menu > Système** : diagnostic ZLink.

## Ce qui reste à tester sur le poste

- La fenêtre CarPlay flottante : elle dépend de ZLink et d'Android, je ne peux pas la promettre avant un test. Le « Diagnostic ZLink » (Menu > Système) donne les informations pour l'étudier.
- La luminosité : elle règle toujours l'écran du launcher. Pour tout le poste (CarPlay compris), autorise une fois « Autoriser » dans Menu > Écran. Selon le poste, le boîtier peut garder la main.
- Le Bluetooth : si le poste le gère avec son propre boîtier, Android affiche « Indisponible ». Sur Android 13 et plus, le bouton ouvre les réglages du poste.
- La pochette et le titre de la musique : pas encore branchés.

## 1. Fabriquer l'app SANS Android Studio (avec GitHub, depuis ton navigateur)

Si ton Mac est trop ancien, GitHub fabrique l'app pour toi, gratuitement.

1. Crée un compte gratuit sur https://github.com (si tu n'en as pas).
2. Clique sur **+ > New repository**. Nom : `golf6-accueil`, choisis **Private**, puis **Create repository**.
3. Sur la page du dépôt, clique sur **uploading an existing file**.
4. Dézippe `Golf6Accueil.zip`, ouvre le dossier, sélectionne tout son contenu (Cmd + A) et glisse-le dans la page. Attends la fin du chargement, puis **Commit changes**.
5. Les dossiers cachés ne se glissent pas toujours : clique sur **Add file > Create new file**, écris comme nom `.github/workflows/build.yml` (les « / » créent les dossiers), colle le contenu du fichier `COLLER-DANS-GITHUB.txt`, puis **Commit changes**.
6. Va dans l'onglet **Actions** : « Fabriquer l'application » démarre tout seul (sinon : clique dessus puis **Run workflow**). Compte 3 à 6 minutes, jusqu'à la coche verte.
7. Clique sur l'exécution terminée, descends jusqu'à **Artifacts** et télécharge `accueil-golf6-apk`. Dézippe-le : tu obtiens `app-debug.apk`.

Si l'exécution est en rouge (échec), ouvre-la, copie le message d'erreur et envoie-le moi.

## 1 bis. Avec Android Studio (si ton Mac le permet)


1. Installe **Android Studio** (gratuit) : https://developer.android.com/studio
   Ouvre-le et laisse l'assistant de première installation tout télécharger.
2. Dézippe ce dossier, puis dans Android Studio : **File > Open** et choisis le dossier `Golf6Accueil`.
3. Attends la fin de la barre de progression en bas (la première fois, ça télécharge des éléments : 5 à 15 minutes).
   Si Android Studio propose « Install missing SDK » ou « Update », clique sur **Install** / **Accepter**.
4. Menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Quand une bulle « APK(s) generated » apparaît en bas à droite, clique sur **locate**.
   Le fichier s'appelle `app-debug.apk`.

## 2. L'installer sur le poste

1. Copie `app-debug.apk` sur une clé USB.
2. Branche la clé sur le poste, ouvre son gestionnaire de fichiers et touche le fichier.
3. Si le poste le demande, autorise l'installation depuis cette source, puis touche **Installer**.

## 3. Le mettre comme accueil

1. Appuie sur le bouton Accueil du poste : Android demande quelle application utiliser.
   Choisis **Accueil Golf 6** puis **Toujours**.
   (Sinon : Réglages > Applis > Applis par défaut > Application d'accueil.)
2. Autorise la localisation si le poste le demande (c'est pour la météo).

## 4. Premier lancement

- Touche **CarPlay** : si l'app n'est pas trouvée toute seule, une liste s'affiche. Choisis l'application CarPlay de ton poste, une seule fois.
- Pour changer l'appli d'un raccourci : **appuie longtemps** dessus (environ une seconde).
- La météo demande une connexion internet (par exemple le partage de connexion de ton téléphone). Sans internet, elle affiche « Météo indisponible ».

## Mode clair / sombre

- Par défaut (mode **Automatique**), l'accueil passe en sombre quand le poste passe en mode nuit (par exemple quand tu allumes tes feux, si ton poste le gère) ou quand le soleil est couché.
- Tu peux forcer Jour ou Nuit dans Menu > Écran.

## Message vocal

À l'allumage du poste, une voix dit « Bonjour » suivi du prénom (« Bonsoir » à partir de 18 h). 
- La voix est celle du poste (moteur de synthèse vocale d'Android) : je choisis une voix française féminine si le poste en a une.
- Si rien ne se dit : Réglages > Langue et saisie > Synthèse vocale, vérifie que le français est installé, et que le volume du poste n'est pas coupé.
- Pour changer le prénom ou le délai : ouvre `MainActivity.java`, les premières lignes de la classe.

## En cas de souci

Retourne dans Réglages > Applis > Applis par défaut > Application d'accueil et remets l'ancien accueil. Rien n'a été supprimé sur le poste.
