# Architecture de Cockpit

## Vue d'ensemble

```
Poste Android ─ MainActivity (Java) ─ WebView ─ assets/index.html (interface)
                    │                              │
                    │   pont Android ⇄ JavaScript  │
                    ├─ lecture des infos de la voiture (propriétés système, diffusions)
                    ├─ voix, volume, touches Accueil / Retour
                    ├─ PhoneServer : mini serveur web pour le téléphone
                    └─ UpdateProvider : installation des mises à jour
```

- **Identifiant de l'application :** `com.golf6.home` (ne pas changer : voir [SIGNATURE.md](SIGNATURE.md)).
- **SDK :** `minSdk 21`, `targetSdk 34`, Java 17.

## Fichiers principaux

| Fichier | Rôle |
|---|---|
| `MainActivity.java` | Activité unique : WebView, pont JavaScript, informations véhicule, voix, volume, luminosité, Wi-Fi, mises à jour |
| `PhoneServer.java` | Serveur HTTP local (port 8765) pour personnaliser Cockpit depuis le téléphone ; accès protégé par un code, uniquement sur le réseau local |
| `UpdateProvider.java` | Fournisseur de fichiers utilisé pour installer l'APK téléchargé |
| `MediaListener.java` | Lecture des informations du lecteur multimédia |
| `assets/index.html` | Toute l'interface : accueil, réglages, personnalisation, profils, assistant de démarrage |
| `assets/m3d/` | Voiture 3D (voir ci-dessous) |
| `assets/phone/` | Appli web de personnalisation ouverte sur le téléphone |
| `assets/guide/` | Pages du guide d'utilisation |
| `assets/voix/` | Voix d'accueil et alertes |

## Les informations de la voiture

Cockpit lit ce que le boîtier CAN du poste met à disposition des autres applications, notamment sur les postes équipés de CarCAN (propriétés `persist.vendor.cem.*` et diffusions `hcn.*`). Il obtient ainsi les portes, la vitesse, le radar, la marche arrière et les avertissements.

Les informations que le boîtier ne partage pas (carburant, kilométrage, régime…) restent **indisponibles** : Cockpit l'affiche clairement et permet de saisir une valeur à la main.

## La voiture 3D

Pour rester fluide sur des postes à 2 Go de RAM, la voiture n'est **pas** calculée en direct. Elle est pré-calculée avec three.js, puis assemblée en :
- vidéos de rotation (une par thème, avec et sans phares) ;
- vidéos de plongée dans l'habitacle ;
- images pour chaque combinaison de portes et le coffre.

Le script `assets/m3d/m3p.js` lit ces vidéos et ces images. Les outils de rendu ne sont pas dans ce dépôt.

## Personnalisation depuis le téléphone

`PhoneServer` expose une petite interface web. Le téléphone scanne un QR code qui contient l'adresse du poste et un code d'accès. Les modifications passent par les fonctions `phoneCatalog`, `phoneLayout` et `phoneSet` de l'interface, jamais par un accès direct aux fichiers.

## Compilation

Voir le [README](../README.md#installation). Le workflow `.github/workflows/build.yml` fabrique l'APK, vérifie sa signature avec `apksigner`, puis publie deux artefacts : `journal-build` (toujours) et `cockpit-apk` (seulement si la compilation et la signature sont valides).

## Distribution et mises à jour

La chaîne GitHub Actions → Netlify → utilisateurs, et les éléments à ne pas modifier, sont décrits dans [DISTRIBUTION.md](DISTRIBUTION.md).
