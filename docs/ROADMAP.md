# Plan de releases : un nouveau petit jeu tous les trois mois pendant trois ans

Point de départ : la version 1.0 (15 jeux) est prévue pour fin 2026, après les tests sur appareils et la mise en place des fiches de magasin. Chaque version suivante ajoute **un** jeu au catalogue débloqué, plus l'entretien nécessaire. Les dates sont des trimestres cibles ; une version glisse plutôt que de sortir incomplète.

## Règles communes à chaque release

- Un seul jeu nouveau, petit : 300 à 800 lignes de Kotlin dans `core/games/<id>/`, aucune ressource image ou son, tout dessiné avec les primitives de `Scene`, une seule classe `Game` et ses tests de règles.
- Titre original EN/FR (jamais le nom d'un jeu classique ni d'une marque) ajouté à `docs/GAME_NAMES.md`, chaînes `title_<id>` sur Android et iOS, clé `StringKey` ajoutée en fin d'énumération uniquement.
- Le jeu passe `HeadlessPlayTest` et `DeterminismTest` sans réglage particulier, puis les prévisualisations (`./gradlew :preview:run`) sont relues.
- Cibles enfants et hors ligne : grandes zones tactiles, très peu de texte, jouable en mode avion, pas de réseau.
- Empreinte : l'APK de téléchargement reste sous 1 Mo et l'IPA aminci sous 5 Mo ; `tools/footprint.sh` et `tools/ios_size.sh` à chaque release.
- Entretien inclus dans la release du trimestre concerné : exigences Google Play (`targetSdk` de l'année, échéance d'août), exigences Apple (Xcode et SDK de l'année, échéance d'avril), mise à jour de Play Billing et de Kotlin, relecture de la checklist QA sur les deux téléphones.
- Une release = un tag `v1.N`, des notes de version EN/FR décrivant le nouveau jeu par sa mécanique, et une capture mise à jour dans les fiches.

## Calendrier

| Version | Trimestre | id | Titre EN / FR | Mécanique | Joueurs | Pourquoi à ce moment |
|---|---|---|---|---|---|---|
| 1.0 | T4 2026 | — | (15 jeux) | Lancement | — | Base |
| 1.1 | T1 2027 (mars) | maze | Maze Run / Labyrinthe | Labyrinthe généré (parcours en profondeur), déplacer le pion par swipe jusqu'à la sortie, taille croissante | 1 | Très simple, idéal pour roder le processus de release |
| 1.2 | T2 2027 (juin) | boxes | Dots and Boxes / Petits carrés | Grille de points, tracer un côté, fermer un carré donne un point et rejoue, IA gloutonne avec anticipation à un coup | 1 ou 2 | Jeu à deux pour les vacances d'été |
| 1.3 | T3 2027 (sept.) | colorcode | Color Code / Code couleur | Deviner une combinaison de 4 couleurs en 8 essais, indices « bien placé / mal placé » sous forme de pastilles | 1 | Déduction, rentrée |
| 1.4 | T4 2027 (déc.) | pegjump | Peg Jump / Saute-billes | Solitaire à billes en croix, sauter par-dessus une bille pour la retirer, gagner en n'en laissant qu'une ; affichage du nombre restant | 1 | Puzzle calme pour les fêtes |
| 1.5 | T1 2028 (mars) | crossing | Crossing / Traversée | Traverser des voies où circulent des blocs à vitesses différentes, tap pour avancer, swipe latéral pour se décaler ; vitesse croissante | 1 | Jeu d'action, équilibre avec les puzzles |
| 1.6 | T2 2028 (juin) | checkers | Checkers / Dames | Damier 10×10 (règles françaises simplifiées : prise obligatoire, dames volantes), IA minimax profondeur 4 réutilisant `ai/Minimax.kt` | 1 ou 2 | Jeu à deux pour l'été ; réutilise l'IA existante |
| 1.7 | T3 2028 (sept.) | wordhunt | Word Hunt / Mots mêlés | Grille de lettres 8×8 à 12×12 avec 6 à 10 mots à trouver en glissant le doigt, listes de mots fr/en déjà embarquées | 1 | Rentrée, valorise les listes du Pendu |
| 1.8 | T4 2028 (déc.) | boxpusher | Box Pusher / Pousse-caisses | Pousser des caisses sur des cibles, niveaux générés ou embarqués (50 niveaux de 8×8 en quelques Ko), annulation du dernier coup | 1 | Puzzle de réflexion, reprenable |
| 1.9 | T1 2029 (mars) | switchoff | Switch Off / Éteins tout | Grille 5×5 de lampes, toucher inverse la case et ses voisines, éteindre tout en un minimum de coups ; grilles générées solvables | 1 | Petit et rapide après le gros Pousse-caisses |
| 1.10 | T2 2029 (juin) | sowing | Seed Sowing / Awalé | Semis de graines sur 2×6 cases, captures à 2 ou 3, IA minimax profondeur 5 | 1 ou 2 | Jeu à deux pour l'été, domaine public |
| 1.11 | T3 2029 (sept.) | pipes | Pipe Flow / Tuyaux | Faire tourner des tuiles pour relier la source à la sortie, grilles générées à partir d'un chemin puis brouillées | 1 | Puzzle visuel, rentrée |
| 1.12 | T4 2029 (déc.) | dodge | Dodge / Esquive | Course sans fin sur 3 couloirs, swipe pour changer de couloir, obstacles et bonus, meilleur score | 1 | Jeu d'action pour clore les trois ans |

Bilan sur trois ans : 27 jeux au total, dont 6 jeux d'action, 14 puzzles, 5 jeux à deux sur le même écran, 2 jeux de lettres. Chaque jeu du calendrier a une mécanique du domaine public, un titre original et aucune dépendance à des ressources externes.

## Réserve (si un jeu du calendrier est abandonné)

| id | Titre EN / FR | Mécanique | Remarque |
|---|---|---|---|
| fiveinline | Five in Line / Cinq alignés | 15×15, aligner cinq pions, IA minimax | Variante proche de Quatre alignés |
| navalbattle | Naval Battle / Bataille navale | Placer des navires, tirer sur une grille cachée, contre l'ordinateur | Le mode à deux nécessite de passer l'appareil |
| dominoes | Dominoes / Dominos | Chaîne de dominos, points dessinés en cercles, IA simple | Plus de règles à expliquer |
| tower | Tower Stack / Tours | Déplacer des disques d'une tour à l'autre sans poser un grand sur un petit | Très court à implémenter |
| quickmath | Quick Math / Calcul rapide | Additions et soustractions chronométrées, quatre réponses au choix | Éducatif, texte plus présent |

## Jeux écartés et pourquoi

- Blocs qui tombent (tétrominos) : les ayants droit poursuivent activement les clones, y compris sur l'apparence.
- Bulles à tirer, tangram, échecs : trop de physique, de manipulation ou d'IA pour rester « petit ».
- Tout jeu nécessitant des images, des sons enregistrés ou des textes longs.

## Ce que chaque release ne change pas

Le modèle reste le même : trois jeux gratuits, un seul achat qui débloque tout, y compris les jeux ajoutés après coup. Les acheteurs reçoivent donc chaque nouveau jeu sans payer davantage, ce qui est l'argument des notes de version et des fiches de magasin.
