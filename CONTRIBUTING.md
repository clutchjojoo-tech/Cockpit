# Contribuer à Cockpit

Merci de t'intéresser à Cockpit. Ce guide explique comment proposer une amélioration sans perdre de temps.

## Façons d'aider

- **Tester sur ton poste** et dire ce qui marche ou non (modèle du poste, version d'Android, boîtier CAN).
- **Signaler un bug** avec le modèle [Bug](https://github.com/clutchjojoo-tech/cockpit/issues/new/choose).
- **Proposer une idée** avec le modèle « Idée ».
- **Traduire** ou corriger un texte.
- **Corriger ou améliorer le code** par une *pull request*.

## Avant de commencer

1. Cherche dans les [tickets](https://github.com/clutchjojoo-tech/cockpit/issues) si le sujet existe déjà.
2. Pour un changement important, ouvre d'abord un ticket pour en parler : ça évite un travail qui ne serait pas retenu.

## Mettre en place le projet

Prérequis : JDK 17, Gradle 8.7, Android SDK 34 (voir le [README](README.md#prérequis)).

```bash
git clone https://github.com/clutchjojoo-tech/cockpit.git
cd cockpit
gradle assembleDebug
```

Sans rien installer, ouvre une pull request : la compilation de vérification se lance toute seule (sans signature et sans APK).

### À propos des APK que tu compiles

La clé de signature actuelle (`app/cockpit.keystore`, avec ses mots de passe dans `app/build.gradle`) est **dans le dépôt public**. Ce n'est donc **pas** une clé personnelle ni privée : toute personne qui clone ou *fork* le dépôt la récupère.

Concrètement :
- un APK compilé sur ton ordinateur avec le dépôt tel quel est signé avec **cette clé publique**, la même que les APK officiels ;
- le workflow **Fabriquer l'application** est verrouillé sur le dépôt officiel : dans ton *fork*, il est ignoré ;
- **ne distribue pas** un APK que tu as compilé, et ne considère jamais un APK comme officiel parce qu'il est signé avec cette clé. Seul l'APK du [site officiel](https://cockpitcarplay.netlify.app/) est fiable ;
- ne modifie jamais la signature dans une pull request.

Détails dans [docs/SIGNATURE.md](docs/SIGNATURE.md).

## Où se trouve quoi

| Dossier | Contenu |
|---|---|
| `app/src/main/java/com/golf6/home/` | Code Android (activité, serveur téléphone, mises à jour) |
| `app/src/main/assets/index.html` | Interface de Cockpit |
| `app/src/main/assets/m3d/` | Voiture 3D pré-calculée (vidéos et images) |
| `app/src/main/assets/phone/` | Appli web de personnalisation depuis le téléphone |
| `docs/` | Documentation |

Les images de la voiture sont calculées hors du dépôt. Si ta modification touche la 3D, décris-la dans le ticket avant de produire de gros fichiers.

## Règles pour une pull request

- **Une modification = une pull request**, avec un titre clair.
- Explique **ce qui change, pourquoi, et sur quel poste tu as testé**.
- Ne change pas l'identifiant `com.golf6.home` : il est nécessaire aux mises à jour des utilisateurs.
- **Ne touche jamais à la signature** (`cockpit.keystore`, mots de passe, `signingConfigs`) : voir [docs/SIGNATURE.md](docs/SIGNATURE.md).
- Garde les fichiers légers : pas de vidéo ou d'image volumineuse sans en avoir parlé. Un fichier ajouté alourdit l'APK de tous les utilisateurs.
- Quand une information de la voiture n'est pas disponible, Cockpit l'indique. N'affiche jamais une valeur inventée.
- Vérifie que la compilation passe avant de demander une relecture. Chaque pull request est vérifiée automatiquement par GitHub Actions : cette vérification compile le code, **sans signer ni publier d'APK** et sans aucun secret. Seuls les mainteneurs fabriquent l'APK officiel.

## Messages de commit

Phrases courtes, à l'impératif, en français : `Corrige le compteur en mode clair`, `Ajoute la langue italienne`.

## Code de conduite

En participant, tu acceptes le [code de conduite](CODE_OF_CONDUCT.md).
