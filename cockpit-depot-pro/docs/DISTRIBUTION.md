# Distribution de Cockpit

Ce document décrit comment l'APK officiel arrive jusqu'aux utilisateurs, et ce qu'il ne faut **pas** changer sans en parler au propriétaire du projet : une modification peut empêcher les versions déjà installées de se mettre à jour.

## La chaîne, de bout en bout

```
GitHub Actions ──► artefact « cockpit-apk » ──► dossier du site ──► Netlify ──► utilisateurs
 (compile + signe)   (app-debug.apk)           (dépôt manuel)    cockpitcarplay     ├─ téléchargement direct
                                                                  .netlify.app      └─ mise à jour intégrée
```

1. **GitHub Actions** fabrique l'APK officiel (workflow **Fabriquer l'application**, lancé à la main par un mainteneur) et le publie dans l'artefact `cockpit-apk` si la compilation et la signature sont conformes.
2. Le mainteneur récupère `app-debug.apk` et l'ajoute au **dossier du site** avec le `version.json` correspondant.
3. Le dossier est déposé sur **Netlify** (`https://cockpitcarplay.netlify.app/`).
4. Les utilisateurs téléchargent l'APK depuis le site, et les applications déjà installées le récupèrent via le système de mise à jour intégré.

**GitHub n'envoie rien automatiquement sur Netlify.** L'étape 3 est manuelle, et ce dépôt ne contient pas les fichiers du site. Les modifications faites sur GitHub (documentation, workflow, README) n'ont donc aucun effet sur le site ni sur les mises à jour.

## Ce que fait l'application pour se mettre à jour

D'après le code de l'application (`assets/index.html` et `MainActivity.java`) :

1. L'adresse du site est fixée dans l'application : `UPDATE_URL = 'https://cockpitcarplay.netlify.app'`.
2. Elle lit `https://cockpitcarplay.netlify.app/version.json?t=<horodatage>`, un petit fichier JSON :
   ```json
   {"version": "2.5", "apk": "api/telecharger?r=1", "note": "…"}
   ```
3. Si `version` est strictement supérieure à la version installée (comparaison numérique, par exemple 2.10 > 2.9), elle propose la mise à jour.
4. L'adresse de l'APK est le champ `apk` ajouté à l'adresse du site, soit :
   `https://cockpitcarplay.netlify.app/api/telecharger?r=1`
5. Cette adresse est une fonction Netlify (`netlify/functions/compteur.mjs`) qui ajoute 1 au compteur de téléchargements, puis **redirige (302) vers `/app-debug.apk`**. L'application suit la redirection.
6. L'application télécharge le fichier dans son cache (`cockpit-maj.apk`, refusé s'il fait moins de 100 000 octets), puis demande à Android de l'installer via `content://com.golf6.home.maj/cockpit-maj.apk`.
7. Android n'accepte l'installation que si l'APK a **la même signature** et un `versionCode` **plus élevé** que l'application installée.

Le bouton de téléchargement du site pointe, lui, directement vers `app-debug.apk`. Le fichier est servi avec le type `application/vnd.android.package-archive` en téléchargement (`_headers`), et `version.json` n'est pas mis en cache.

## Cockpit Demo : un projet à part

Le site de démonstration [cockpitcarplaydemo.netlify.app](https://cockpitcarplaydemo.netlify.app/) est un projet web **indépendant** : hébergé sur Netlify, sans lien avec ce dépôt, et **sans rôle dans la distribution de l'APK ni dans les mises à jour**. L'application Android ne l'utilise jamais. Ses fichiers ne doivent pas être mélangés à ceux de ce dépôt.

## Ce qu'il ne faut pas changer sans autorisation

| Élément | Pourquoi |
|---|---|
| L'adresse `https://cockpitcarplay.netlify.app` et `UPDATE_URL` dans l'application | Les versions installées lisent cette adresse en dur. Si elle change, elles ne trouvent plus les mises à jour. |
| Le chemin `/version.json` et ses champs `version`, `apk`, `note` | Format lu par toutes les versions installées. |
| Le chemin `/api/telecharger?r=1` et la fonction `compteur` | C'est l'adresse de téléchargement qu'utilisent les versions installées. |
| Le nom de fichier `app-debug.apk` à la racine du site | La fonction redirige vers ce nom, et le site y pointe. |
| L'identifiant `com.golf6.home` | Sinon Android installe une autre application au lieu de mettre à jour. |
| La clé de signature | Sinon Android refuse toutes les mises à jour. Voir [SIGNATURE.md](SIGNATURE.md). |
| Le nom de l'artefact `cockpit-apk` et le nom `app-debug.apk` qu'il contient | Le mainteneur récupère ce fichier pour le site. |

## Publier une nouvelle version (mainteneurs)

1. Augmente `versionCode` **et** `versionName` dans `app/build.gradle`, et la constante `APP_VERSION` dans `assets/index.html`. Les trois doivent rester cohérents.
2. Lance le workflow **Fabriquer l'application** sur la branche par défaut du dépôt officiel.
3. Vérifie que l'exécution est verte et télécharge l'artefact **cockpit-apk**.
4. Dans le dossier du site : remplace `app-debug.apk` (même nom) et mets à jour `version.json` avec la nouvelle `version` (sans toucher à `apk`).
5. Dépose le dossier sur Netlify.
6. **Teste la mise à jour sur un poste qui a l'ancienne version** (bouton de vérification des mises à jour dans les réglages), avant d'annoncer la sortie.

## Ce qui n'a pas pu être vérifié depuis le dépôt

La description ci-dessus vient du code de l'application et des fichiers du site tels qu'ils ont été préparés. L'état réel de ce qui est publié sur Netlify aujourd'hui (contenu de `version.json`, version de l'APK en ligne) ne peut se vérifier que sur le site lui-même.
