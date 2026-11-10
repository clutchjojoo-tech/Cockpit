# Signature des APK

Ce document explique comment Cockpit est signé et pourquoi il ne faut **jamais** changer la clé à la légère.

## Comment ça fonctionne aujourd'hui

- Les APK sont signés avec `app/cockpit.keystore` (alias `cockpit`, certificat `CN=Cockpit, O=Malola Studio, C=FR`).
- La configuration est dans `app/build.gradle` (bloc `signingConfigs`).
- Les APK distribués sur le site officiel sont signés avec cette clé : l'empreinte du certificat de l'APK publié est identique à celle du keystore.

**Empreinte SHA-256 du certificat de référence :**

```
3F:65:36:19:D1:04:80:04:54:55:1E:8B:18:26:5F:64:8E:37:5B:2E:1E:CB:8E:B5:12:BC:40:CD:8C:2E:6D:5D
```

Le workflow de compilation vérifie l'APK avec `apksigner verify --print-certs` et compare l'empreinte SHA-256 du certificat à cette référence. Si elle diffère, la compilation échoue et l'APK n'est pas proposé au téléchargement.

## Pourquoi la clé est vitale pour les mises à jour

Cockpit se met à jour en téléchargeant un APK depuis le site officiel, puis en demandant à Android de l'installer par-dessus l'application existante. Android n'accepte cette installation **que si la signature est identique**. Sinon, il affiche « Application non installée ».

Changer la clé ou perdre le keystore a donc deux conséquences :
- plus aucune mise à jour possible pour les utilisateurs existants ;
- ils doivent désinstaller Cockpit puis le réinstaller, et perdent leurs réglages.

**Règle : on ne supprime pas le keystore, on ne change pas la clé et on ne change pas l'alias ni les mots de passe sans plan de migration.**

## Le risque d'avoir la clé dans un dépôt public

Les mots de passe sont dans `app/build.gradle` et le keystore est dans le dépôt. Toute personne peut donc signer un faux APK avec la vraie clé. Android le considérerait comme une mise à jour légitime de Cockpit, à condition que l'utilisateur l'installe lui-même.

Retirer le fichier du dépôt ne suffit pas : il reste dans l'historique Git. La clé doit être considérée comme **connue**.

## Plan de sécurisation

### Étape 1 : mettre la clé dans les secrets GitHub (sans coupure)

1. Encoder le keystore en texte : `base64 -i app/cockpit.keystore | pbcopy` (sur Mac).
2. Dans **Settings › Secrets and variables › Actions**, créer quatre secrets :

| Secret | Valeur |
|---|---|
| `COCKPIT_KEYSTORE_BASE64` | le texte copié |
| `COCKPIT_KEYSTORE_PASSWORD` | le mot de passe du keystore |
| `COCKPIT_KEY_ALIAS` | `cockpit` |
| `COCKPIT_KEY_PASSWORD` | le mot de passe de la clé |

3. Modifier `app/build.gradle` pour lire ces valeurs quand elles existent et garder le keystore du dépôt sinon, de façon à ne jamais interrompre la génération.
4. Vérifier qu'une compilation avec les secrets donne **la même empreinte** que la référence ci-dessus (le workflow le contrôle).

Tant que cette vérification n'a pas réussi, on ne retire rien du dépôt.

### Étape 2 : fermer vraiment la faille (plus tard)

Comme la clé actuelle est publique, la solution durable est de passer à **une nouvelle clé gardée secrète**, avec la *rotation de clé* d'Android (schéma de signature APK v3). Les installations existantes acceptent alors la nouvelle clé comme successeur de l'ancienne, sans désinstallation.

Limites à connaître :
- la rotation fonctionne à partir d'**Android 9** ; sur les versions plus anciennes, il faudrait réinstaller ;
- elle demande un test sur un vrai poste avant toute publication ;
- c'est un chantier à part, à ne lancer qu'avec une sauvegarde de l'ancienne clé.

## Contributions et workflow : deux usages séparés

Le workflow `.github/workflows/build.yml` contient deux tâches qui ne se mélangent jamais :

| Déclencheur | Tâche | Signature | APK publié |
|---|---|---|---|
| `pull_request` (contributions) | `verification` | Aucune : elle compile le code, les ressources et les fichiers de l'application, sans fabriquer d'APK | Non |
| `workflow_dispatch` (lancement manuel par un mainteneur, **uniquement dans le dépôt officiel `clutchjojoo-tech/cockpit`** et sur sa branche par défaut) | `build` | Keystore du projet, vérifié avec `apksigner` | Oui, seulement si la compilation et la signature sont conformes |

Les pull requests, y compris celles des *forks*, tournent sans secrets et avec un accès en lecture seule. La tâche `verification` ne fabrique aucun APK. Elles ne peuvent donc ni signer, ni publier l'APK officiel.

La tâche `build` est ignorée dans tout dépôt autre que `clutchjojoo-tech/cockpit` (condition `github.repository`), donc dans les *forks*.

### Réglages GitHub conseillés

Dans **Settings › Actions › General › Fork pull request workflows from outside collaborators**, choisis *Require approval for first-time contributors* (ou *for all outside collaborators*) : un mainteneur approuve chaque première exécution venant d'un inconnu.

Quand les secrets de signature seront ajoutés (étape 1 ci-dessus), ils ne devront être lus que par la tâche `build`, jamais par `verification`.

## Forks, clones et APK compilés par des tiers : ce qui est vrai aujourd'hui

À ce jour, `app/cockpit.keystore` et ses identifiants (`app/build.gradle`) sont dans le dépôt public. **Un fork ou un clone les contient aussi.** Cette clé n'est donc ni personnelle, ni privée, ni propre à un contributeur : elle est publique.

Conséquences, sans détour :

- Le verrou sur la tâche `build` empêche le **workflow officiel** de tourner dans un fork. Il n'empêche **pas** quelqu'un de compiler le projet chez lui.
- Toute personne qui lance `gradle assembleDebug` sur le dépôt tel quel obtient un APK signé avec **la clé officielle** (publique). Android le considère donc comme une mise à jour légitime de Cockpit.
- Une signature conforme ne prouve donc **pas** qu'un APK vient de Malola Studio. Seul l'APK du [site officiel](https://cockpitcarplay.netlify.app/) est à considérer fiable.
- Un contributeur ne doit jamais présenter ou diffuser un APK compilé comme une version de Cockpit.

Ce risque est connu et assumé tant que la clé reste dans le dépôt, pour ne pas couper les mises à jour des utilisateurs. La vraie solution est décrite plus haut (secrets GitHub, puis nouvelle clé avec rotation) ; elle sera décidée avec le propriétaire du projet.
