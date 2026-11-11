# Sécurité

## Signaler une faille

Si tu penses avoir trouvé une faille de sécurité dans Cockpit, **n'ouvre pas de ticket public**.

Utilise plutôt le signalement privé de GitHub : onglet **Security**, puis **Report a vulnerability**. Si cet onglet n'est pas disponible, contacte Malola Studio par les moyens indiqués sur le [site officiel](https://cockpitcarplay.netlify.app/).

Indique :
- ce que tu as trouvé et où,
- comment le reproduire,
- la version de Cockpit concernée.

Nous répondons dès que possible et te tenons au courant de la correction.

## Versions prises en charge

Seule la **dernière version** publiée sur le [site officiel](https://cockpitcarplay.netlify.app/) reçoit des corrections.

## Télécharger Cockpit en sécurité

Télécharge Cockpit **uniquement depuis le site officiel**. Un APK reçu d'une autre source n'est pas fiable, même s'il porte le nom de Cockpit.

Android n'accepte une mise à jour de Cockpit que si le nouvel APK est signé avec la même clé que l'application déjà installée.

## Ce que la signature prouve (et ne prouve pas)

La clé de signature actuelle est publique, puisqu'elle est dans le dépôt. Une signature conforme ne prouve donc pas qu'un APK vient de Malola Studio : seul le téléchargement depuis le site officiel est fiable. Voir [docs/SIGNATURE.md](docs/SIGNATURE.md).

## Ce qui est volontairement local

- Le serveur de personnalisation depuis le téléphone fonctionne uniquement sur le réseau local (Wi-Fi ou partage de connexion), protégé par un code propre à chaque poste. Il ne peut modifier que les réglages de Cockpit.

## Signature des applications

La gestion de la clé de signature est décrite dans [docs/SIGNATURE.md](docs/SIGNATURE.md).
