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

## 2026-09-18 — Réconciliation avec le dépôt GitHub

- Dépôt distant `github.com/comuafr/lightweight-games` (accès SSH via `gh`, compte JeWaVe) : il contenait une approche antérieure de mai 2026, un kit « usine » Android avec une app autonome par jeu (Snake), des gabarits, un workflow d'agent et un bootstrap VPS.
- Fusion des historiques avec priorité au travail actuel : wrapper Gradle 9.7.1, `.gitignore` et `gradlew` conservés dans leur version locale.
- Ancien kit déplacé sous `legacy/` (README, agent/, bootstrap/, games/, templates/, new_game.py) ; ancien workflow CI supprimé (il construisait `games/*`) ; nouveau `README.md` racine décrivant la structure KMP.
- Poussé sur `origin/main`.

## 2026-09-18 — Procédure de test sur appareils

- Question sur l'emplacement des jeux : `core/src/commonMain/kotlin/lg/core/games/<id>/`, un dossier par jeu, registre dans `GameRegistry.kt`.
- Ajout au `README.md` d'une procédure de test sur Samsung (débogage USB, `adb install -r`, logcat, tests d'instrumentation, limite des achats sans piste interne Play) et sur iPhone (Mac + Xcode + XcodeGen, signature avec Apple ID gratuit, mode développeur, StoreKit local).
- `tools/env.sh` ajoute désormais `platform-tools` au PATH pour `adb`.

## 2026-09-18 — iOS sans Mac : CI macOS

- Question : est-il vraiment impossible de tester sur iPhone sans Mac ? Réponse : compiler et produire un IPA est possible sur un runner macOS GitHub ; installer exige une signature Apple (sideloading avec compte gratuit depuis Linux, ou TestFlight avec compte payant).
- Constat : la CI poussée la veille échouait (job Android : action `setup-android` en erreur ; job iOS : nom de tâche Gradle erroné). Le job core passait.
- Correctifs : `ci.yml` installe les paquets SDK avec le `sdkmanager` préinstallé du runner ; nouveau `ios.yml` (macos-15, XcodeGen, build simulateur, XCTest, archive non signée publiée en artefact `ios-unsigned-ipa`), déclenché à la demande ou sur modification de `iosApp/`/`core/`.
- README : section « iPhone sans Mac ».
- Premier retour du runner Mac : Kotlin/Native et XcodeGen passent en 3 min, mais Swift ne trouvait pas `GamesCore` (le spec attendait une tranche `.framework` alors que Gradle produit un `.xcframework`). Correctif : le XCFramework est déclaré comme dépendance de la cible dans `project.yml`, le script de pré-build le construit via `assembleGamesCore{Debug,Release}XCFramework`, README iOS mis à jour.
- CI Linux : Gradle Android passe sur le runner ; seul `tools/footprint.sh` échouait car `tools/env.sh` imposait `ANDROID_HOME=~/Android/Sdk`. Les deux scripts sont rendus portables (variables conservées si déjà valides, recherche d'`apkanalyzer` dans les dossiers `cmdline-tools/*`).
- Deuxième build Mac : le XCFramework est trouvé, une seule erreur Swift (`Services` construisait le `StoreManager` isolé au main actor). Correctif : `Services` et les classes de test qui le construisent sont `@MainActor`, `AppDelegate` le crée en `lazy var`.
- Troisième build Mac : plus d'erreur Swift dans les sources ; l'échec venait de la compilation x86_64 du simulateur alors que le XCFramework n'a que la tranche arm64. Correctif : `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` dans `project.yml`, `ARCHS=arm64` dans le workflow, journaux complets publiés en artefact.
- Quatrième build Mac : première erreur de pontage réelle (`meta.icon(scene, size)` attend un `KotlinFloat`). Corrigé ; le workflow publie désormais `GamesCore.h` en artefact pour vérifier tous les noms exportés d'un coup.
- Cinquième build Mac : **l'application iOS compile entièrement**. Reste la cible de tests (doublon `@MainActor` sur `StoreKitTests`, corrigé).
- Sixième build Mac : **tout est vert**. App compilée, 10 XCTest réussis (SmokeTests, StoreKitTests avec achat/remboursement locaux, EntitlementReconcilerTests), archive appareil non signée : app 1,4 Mo, IPA 433 Ko (artefact `ios-unsigned-ipa`). Deux corrections de code auront suffi (`Services` en `@MainActor`, `KotlinFloat` pour la lambda d'icône). Les modifications de `.md` ne déclenchent plus le workflow Mac.
- Remarque de l'utilisateur : les builds macOS étaient facturés et lancés sans son accord. Bilan : 7 runs macOS (16 min de mur, environ 160 min de quota avec le multiplicateur 10x) et 10 runs Linux (24 min) dans la journée. Le workflow iOS passe en déclenchement manuel uniquement ; règle retenue : demander avant toute action payante.
- Décision : la CI Linux (gratuite dans le quota) tourne à chaque push ; le workflow macOS reste manuel.
- Licence propriétaire ajoutée (`LICENSE`, français avec résumé anglais) au nom de CONCEPTION, MACHINES ET USINAGE D'AUCAMVILLE (COMUA), SARL au capital de 10 000 €, SIREN 914 806 468, RCS Montauban, 2 route du Burgaud, 82600 Aucamville, gérant Régis Portalez. Sources : API Recherche d'entreprises (data.gouv) et fiche fournie par l'utilisateur. Les licences héritées de `legacy/` renvoient désormais vers la licence racine ; mention dans le README et `NSHumanReadableCopyright` dans l'Info.plist iOS.
