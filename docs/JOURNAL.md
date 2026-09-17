# Journal de session

Historique des échanges et des décisions, mis à jour à chaque tour. Le plus récent en bas.

## 2026-09-17 — Session 1 : plan et implémentation M0 à M5

### Demande initiale
Créer un plan pour une app Android/iOS de mini-jeux : version gratuite avec 3 jeux (Snake + 2), un achat intégré unique qui débloque tout le catalogue (plus de 10 jeux), sans publicité, sans écran d'interruption, sans faux clic, jeux minimaux, hors ligne, empreinte mémoire minimale en Kotlin/Objective-C. Cibles : parents occupant les enfants en train/voiture/avion, et personnes voulant un jeu léger installable avec peu de réseau.

### Choix validés
- Architecture : Kotlin Multiplatform pour la logique (`core`), UI native fine (Kotlin + Canvas sur Android, Swift + UIKit/CoreGraphics sur iOS). Swift retenu à la place d'Objective-C (StoreKit 2).
- Jeux gratuits : Snake, 2048, Casse-briques.
- Exécution en mode auto, implémentation déléguée à des agents Sonnet, plan sauvegardé dans `PLAN.md`.

### Déroulé
1. Plan rédigé (catalogue de 15 jeux, choix structurants, layout du dépôt, module core, couches plateformes, IAP, empreinte, tests, jalons M0 à M5, risques).
2. Outillage installé sans root : JDK Temurin 21 dans `~/.jdks/jdk-21`, SDK Android (platform 36, build-tools 36.0.0) dans `~/Android/Sdk`, script `tools/env.sh`.
3. M0 : build Gradle (Gradle 9.7.1, Kotlin 2.4.20, AGP 9.4.0), core avec `Game`/`Scene`/`GameSession`/`GestureDetector`/`Rng`, Snake, 50 tests ; app Android (26 Ko) ; app iOS (spec XcodeGen, non compilée). Correctifs : grille Snake en paysage et score dupliqué ; plugins déclarés une fois dans le build racine pour réparer la cible iOS.
4. M1 : 2048 (animation, reprise) et Casse-briques ; audio PCM procédural sur les deux plateformes ; sons émis une seule fois par changement d'état.
5. M2 : `Entitlement` + `Obfuscator` dans le core ; Play Billing 8 + paywall + barrière parentale sur Android (permission INTERNET transitive retirée, seule `com.android.vending.BILLING` reste, APK 243 Ko) ; StoreKit 2 + paywall + `Products.storekit` sur iOS.
6. M3 : Séquence, Paires, Taquin, Morpion, Raquettes, Quatre en ligne, minimax ; 116 tests.
7. M4 : Oiseau, Démineur, Pendu (listes de mots curatées fr/en), Reversi, Sudoku, Nonogramme, `CoreConfig.languageTag` ; 178 tests. Correctifs : potence du Pendu bornée et centrée, libellés de difficulté hors du chemin de rendu.
8. Outil `preview/` : rendu JVM de chaque jeu en PNG (61 fichiers), catalogue vérifié visuellement.
9. M5 : chaînes localisées des 15 titres et difficultés (Android + iOS, script de contrôle des ordinaux), langue transmise au core, test d'instrumentation Android, versions 1.0.0, icône iOS générée, signature optionnelle par `keystore.properties`, README Android et iOS, fiche store, checklist QA, guide de test des achats, scripts d'empreinte.

### État en fin de session
- Tests : 178 core + 12 Android verts ; APK release 271 Ko de téléchargement ; AAB, lint et test d'instrumentation compilent.
- Non vérifié : compilation iOS (pas de Mac), tests sur appareil (aucun connecté), Play Billing sans permission INTERNET sur appareil réel.
- Écarts au plan : Play Billing tire AndroidX/GMS (+245 Ko) ; projet Xcode remplacé par XcodeGen ; 309 mots fr et 281 en pour le Pendu.

### Prochaines étapes convenues
- L'utilisateur dispose d'un iPhone et d'un Samsung pour tester ; reprise prévue le lendemain.
- À faire : installer l'APK sur le Samsung et dérouler `docs/QA_CHECKLIST.md` ; premier build sur Mac via XcodeGen puis test sur l'iPhone ; créer le produit `unlock_all` dans les consoles.
- Consigne : sauvegarder l'état (commit) et ce journal à chaque tour.
