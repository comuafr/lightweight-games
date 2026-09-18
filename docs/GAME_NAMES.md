# Noms des jeux

Les titres affichés sont des créations originales de COMUA, choisis le 2026-09-18 pour ne reprendre ni le nom d'un jeu classique ni une marque (Snake, Tetris, Breakout, Simon, Memory, Pong, Connect Four / Puissance 4, Flappy Bird, Minesweeper, Othello, Sudoku (marque Nikoli au Japon), Picross, etc.). Les mécaniques elles-mêmes ne sont pas protégeables ; seuls les noms, les graphismes et le code le sont, et les nôtres sont originaux.

Les identifiants internes (`id`) ne changent jamais : ce sont les clés de sauvegarde des scores et des parties. Seules les chaînes `title_<id>` dans `androidApp/src/main/res/values*/strings.xml` et `iosApp/LightweightGames/Localizable.xcstrings` portent les titres.

| id | Anglais | Français |
|---|---|---|
| `snake` | Gobbler | Glouton |
| `2048` | Fusion | Fusion |
| `breakout` | Wall Smash | Casse-mur |
| `sequence` | Color Echo | Écho de couleurs |
| `pairs` | Twins | Jumeaux |
| `sliding` | Tile Shuffle | Glisse-cases |
| `tictactoe` | Three in Line | Trois alignés |
| `paddles` | Paddles | Raquettes |
| `fourinrow` | Drop Four | Quatre alignés |
| `bird` | Chirp | Piou-Piou |
| `mines` | Hidden Bombs | Bombes cachées |
| `hangman` | Secret Word | Mot secret |
| `reversi` | Flip Discs | Retourne-pions |
| `sudoku` | Nine by Nine | Neuf par neuf |
| `nonogram` | Pixel Reveal | Pixel caché |

Ne jamais utiliser les noms classiques dans les fiches de magasin, les captures ou les mots-clés ; décrire la mécanique à la place (« un serpent qui grandit », « fusionner des tuiles », etc.).
