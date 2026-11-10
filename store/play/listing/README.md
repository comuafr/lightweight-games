# Fiche Google Play : tout ce qu'il faut saisir

Textes prêts à coller (un fichier par champ, limites vérifiées) :
`en-US/` et `fr-FR/` : `title.txt` (30), `short_description.txt` (80), `full_description.txt` (4000), `release_notes.txt` (500).
Visuels : `../icon_512.png`, `../feature_1024x500_en.png`, `../feature_1024x500_fr.png`, `../product_icon_unlock_all_512.png` (icône du produit). Les captures sont à faire sur le téléphone (voir plus bas).

## Présence sur le Store > Fiche principale
| Champ | Valeur |
|---|---|
| Langue par défaut | Français (France), fr-FR. Ajouter l'anglais (en-US) dans « Traductions » |
| Nom de l'appli | `Jeux Légers` (fr-FR) / `Light Games` (en-US) |
| Description courte, complète | `fr-FR/` et `en-US/` |
| Icône | `../icon_512.png` (PNG 512×512) |
| Image de présentation | `../feature_1024x500_fr.png` / `_en.png` (1024×500) |
| Captures d'écran téléphone | 2 minimum, 8 maximum (16:9 à 9:16, 320 à 3840 px) |
| Vidéo, tablette | facultatif, laisser vide |

## Paramètres du Store (Store settings)
| Champ | Valeur |
|---|---|
| Type | Jeu |
| Catégorie | Occasionnel (Casual). Alternative : Puzzle si Occasionnel est refusé |
| Tags (jusqu'à 5, à choisir dans la liste proposée) | Arcade, Puzzle, Casual, Board, Family. Prendre les plus proches parmi ceux proposés |
| E-mail de contact | regis.portalez@comua.fr |
| Site Web | https://comuafr.github.io/lightweight-games/ (ou le site de COMUA) |
| Téléphone | obligatoire pour un compte « trader » dans l'UE (coordonnées publiées) |
| Politique de confidentialité | https://comuafr.github.io/lightweight-games/ |

## Contenu de l'application (réponses)
| Rubrique | Réponse |
|---|---|
| Accès à l'application | Oui, une partie est restreinte (achat intégré). Aucun identifiant. Texte pour le relecteur, en anglais, ci-dessous |
| Publicités | Non |
| Classification du contenu | Catégorie Jeu. Violence, sang, sexe, langage, drogues, jeux d'argent : non. Interaction entre utilisateurs, localisation, contenu généré : non. Achats intégrés : oui. Résultat attendu : PEGI 3 / Tous publics |
| Public cible | Tous âges, enfants inclus : lire la politique « Designed for Families » (pas de pub, aucune collecte, barrière parentale devant l'achat, achat clairement annoncé) |
| Sécurité des données | Aucune donnée collectée, aucune partagée. Pas de chiffrement en transit à déclarer (pas de réseau) |
| Identifiant publicitaire | Non (vérifié : pas de permission AD_ID, aucun SDK publicitaire) |
| Statut « trader » | Oui |
| Conditions d'utilisation (URL) | Facultatif, laisser vide |
| Application d'actualité, santé, finance, gouvernement | Non à tout |

### Texte « Any other information required for access » (en anglais)
```
No account or sign-in is required. Three games are free (Gobbler, Fusion, Wall Smash). The other twelve are unlocked by one in-app purchase, "Unlock every game" (product ID unlock_all).

To reach the purchase screen: on the home screen, tap any locked game (padlock icon) or the "Unlock all" link. The purchase button is protected by a parental gate: press and hold it for 3 seconds, then Google Play's purchase sheet opens.

The app works fully offline and contains no ads.
```
Nom du jeu d'identifiants : `Unlock all games (in-app purchase)`. Laisser identifiant et mot de passe vides.

## Produit intégré `unlock_all`
- Titre FR : `Débloquer tous les jeux`. Titre EN : `Unlock every game`.
- Description FR : `Débloque les douze jeux supplémentaires, une seule fois, sans abonnement.`
- Description EN : `Unlocks the twelve extra games, one time, no subscription.`
- Option d'achat `unlock-all`, type Buy, active. Icône : `../product_icon_unlock_all_512.png`.

## Captures d'écran à faire sur le téléphone
Dans cet ordre, en jeu avancé quand c'est possible : catalogue, Glouton, Fusion, Casse-mur (plusieurs briques cassées), Raquettes (deux joueurs), Neuf par neuf, écran de déblocage. Fichiers à placer dans `../screenshots/fr-FR/` puis `en-US/` (changer la langue du téléphone pour les captures anglaises).

## Notes de version 1.0.0
`fr-FR/release_notes.txt` et `en-US/release_notes.txt`.
