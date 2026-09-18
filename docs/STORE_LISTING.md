# Store listing (draft)

Same content on Google Play and the App Store, adapted to each console's fields.

## Names

Game titles are original (renamed on 2026-09-18 to stay clear of any classic-game or brand name); the mapping to the internal ids is in `docs/GAME_NAMES.md`.
- App name: **Light Games** (EN) / **Jeux Légers** (FR)
- Bundle / application id: `fr.regisp.lightgames`
- In-app product: `unlock_all`, non-consumable, "Unlock every game" / "Débloquer tous les jeux"

## Short description (80 chars)
- EN: 15 tiny offline games for kids and grown-ups. No ads, no account, no network.
- FR : 15 mini-jeux hors ligne pour petits et grands. Sans pub, sans compte, sans réseau.

## Full description
EN:

Light Games is a pocket arcade that works everywhere: in the train, in the car, on the plane, or anywhere the network is bad.

- Three games for free: Gobbler, Fusion and Wall Smash.
- One purchase unlocks twelve more: Color Echo, Twins, Tile Shuffle, Three in Line, Paddles, Drop Four, Chirp, Hidden Bombs, Secret Word, Flip Discs, Nine by Nine and Pixel Reveal.
- No ads, no interstitials, no fake buttons, no subscription.
- No account, no tracking, no data collection. The app never needs the network, except the store itself when you buy.
- Tiny download, instant launch, gentle on the battery.
- Big touch targets and very little text, so young children can play alone. Several games are for two players on the same screen.
- A parental gate protects the purchase button.

FR :

Jeux Légers est une salle d'arcade de poche qui marche partout : dans le train, en voiture, en avion, ou dès que le réseau est mauvais.

- Trois jeux gratuits : Glouton, Fusion et Casse-mur.
- Un seul achat débloque douze jeux de plus : Écho de couleurs, Jumeaux, Glisse-cases, Trois alignés, Raquettes, Quatre alignés, Piou-Piou, Bombes cachées, Mot secret, Retourne-pions, Neuf par neuf et Pixel caché.
- Aucune publicité, aucun écran d'interruption, aucun faux bouton, aucun abonnement.
- Aucun compte, aucun suivi, aucune collecte de données. L'application n'a jamais besoin du réseau, sauf la boutique au moment de l'achat.
- Téléchargement minuscule, lancement instantané, douce pour la batterie.
- Grandes zones tactiles et très peu de texte : les jeunes enfants jouent seuls. Plusieurs jeux se jouent à deux sur le même écran.
- Une barrière parentale protège le bouton d'achat.

## Categories and ratings
- Google Play: Games > Casual. Content rating questionnaire: no violence, no user interaction, no data collection. Target audience: declare "everyone" including children only after reviewing the Designed for Families policy (no ads, no data collection, IAP disclosed). The app has no INTERNET permission.
- App Store: Games > Family, secondary Puzzle. Age rating 4+. Do not opt into the Kids category (extra IAP constraints); keep the parental gate anyway.

## Privacy declarations
- Google Play Data safety: no data collected, no data shared, no security practices to declare beyond "data is not encrypted in transit" being not applicable (no transit). Purchases are handled by Google Play.
- App Store privacy nutrition label: "Data Not Collected". `PrivacyInfo.xcprivacy` declares UserDefaults (reason CA92.1) and no tracking.

## Screenshots
Generate with `./gradlew :preview:run` (JVM renderer, PNG per game) for drafts, then replace with real device captures for the final listing: catalog, Gobbler, Fusion, Wall Smash, one two-player game (Paddles), one puzzle (Nine by Nine), the unlock screen with the parental gate.

## Release notes 1.0
- EN: First release. 3 free games, 12 more with a single purchase. No ads, works offline.
- FR : Première version. 3 jeux gratuits, 12 de plus avec un seul achat. Sans pub, hors ligne.
