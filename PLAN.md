# Plan : application de mini-jeux légers, hors-ligne, sans publicité (Android + iOS)

## Contexte

Créer de zéro (répertoire vide) une application mobile de jeux minimalistes. Version gratuite avec 3 jeux, un achat intégré unique non consommable qui débloque tout le catalogue. Aucune publicité, aucun écran d'interruption, aucun faux clic, aucun réseau requis pour jouer, empreinte mémoire et binaire minimale.

Cibles :
1. Parents qui veulent occuper des enfants dans le train, la voiture ou l'avion : mode avion, grandes cibles tactiles, UI peu textuelle, jeux à deux sur un seul écran.
2. Personnes qui veulent un jeu léger installable avec peu de réseau : APK/IPA petit, lancement instantané, pas de compte, pas de SDK analytics.

Choix validés avec l'utilisateur :
- **Kotlin Multiplatform** pour la logique (module `core`), **UI native fine** : Kotlin + Canvas sur Android, **Swift** + UIKit/CoreGraphics sur iOS.
- Jeux gratuits : **Snake, 2048, Casse-briques**.

---

## 1. Catalogue des jeux (15 en v1)

Titres d'affichage sans marque déposée (voir §8). Les ids internes sont stables et servent de clés de stockage.

### Gratuits (3)

| id | Titre FR / EN | Mécanique | Entrée | Joueurs | Temps réel | Reprenable |
|---|---|---|---|---|---|---|
| snake | Serpent / Snake | grille, tick fixe, vitesse croissante | swipe 4 directions | 1 | oui | non |
| g2048 | 2048 | grille 4×4, fusion de tuiles | swipe 4 directions | 1 | animation seulement | oui |
| breakout | Casse-briques / Bricks | balle, raquette, collisions AABB | glisser horizontal | 1 | oui | non |

### Débloqués par l'achat (12), ordonnés par complexité

| # | id | Titre FR / EN | Mécanique | Entrée | Joueurs | Complexité |
|---|---|---|---|---|---|---|
| 1 | sequence | Séquence / Color Sequence | répéter une suite de 4 couleurs | tap | 1 | S |
| 2 | pairs | Paires / Pairs | cartes retournées, symboles procéduraux | tap | 1 ou 2 | S |
| 3 | sliding | Taquin / Sliding Puzzle | 3×3 ou 4×4, permutations solvables | tap / swipe | 1 | S |
| 4 | tictactoe | Morpion / Tic-Tac-Toe | 3×3, IA minimax | tap | 1 ou 2 | S |
| 5 | paddles | Raquettes / Paddles | écran partagé, 2 zones multi-touch | glisser | 1 ou 2 | S |
| 6 | fourinrow | Quatre en ligne / Four in a Row | 7×6, gravité, IA minimax prof. 4 | tap colonne | 1 ou 2 | M |
| 7 | bird | Oiseau / Bird | gravité, tap = impulsion, obstacles | tap | 1 | M |
| 8 | mines | Démineur / Minesweeper | propagation, drapeaux, premier tap sûr | tap / appui long | 1 | M |
| 9 | hangman | Pendu / Hangman | listes de mots fr + en embarquées, clavier dessiné | tap | 1 | M |
| 10 | reversi | Reversi | 8×8, retournements, IA positionnelle | tap | 1 ou 2 | C |
| 11 | sudoku | Sudoku | générateur backtracking + unicité, 3 niveaux | tap + pavé | 1 | C |
| 12 | nonogram | Nonogramme / Nonogram | 5×5 à 15×15, solveur ligne | tap / glisser | 1 | C |

Backlog après v1 : Dames, Solitaire Klondike (rendu de cartes riche, drag & drop complexe). Tout est dessiné procéduralement, aucune image bitmap, aucun fichier audio, aucune police embarquée.

---

## 2. Choix structurants

| Sujet | Décision | Raison |
|---|---|---|
| Rendu | Le core émet une liste de primitives (`Scene`) ; **un seul renderer par plateforme** | 2 renderers au lieu de 30 (15 jeux × 2 OS) ; rendu testable en commonTest |
| Gestes | `GestureDetector` dans le core ; les plateformes n'envoient que des pointeurs bruts | Comportement identique sur les deux OS |
| Overlays (pause, game over, rejouer, 1/2 joueurs) | Dessinés et gérés par le core (`GameSession`) | Aucun écran natif par jeu |
| Dépendances core | Aucune (stdlib Kotlin uniquement, pas de kotlinx-serialization) | Empreinte, build iOS ; sérialisation `k=v;` triviale |
| Android UI | `Activity` du framework, sans AppCompat/Material/RecyclerView | AppCompat ≈ +1 Mo |
| Billing | `com.android.billingclient:billing` (pas `-ktx`, évite kotlinx-coroutines) | Empreinte |
| Permissions Android | Aucune, pas même `INTERNET` (Billing passe par le Play Store) | Argument « aucune donnée ne sort » |
| Sons | Tons PCM générés au démarrage | Empreinte |
| Versions | Kotlin ≥ 2.2.20, AGP ≥ 8.13, Gradle ≥ 8.14, Play Billing ≥ 8.0.0, compileSdk/targetSdk 36, minSdk 24, iOS ≥ 15.0, Xcode ≥ 26 | Exigences stores 2026 (à revérifier au moment de M2) |

---

## 3. Layout du dépôt

```
lightweight-games/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/libs.versions.toml, gradle/wrapper/
├── core/                                # module KMP : Android lib + XCFramework iOS
│   ├── build.gradle.kts
│   └── src/commonMain/kotlin/lg/core/   # tout le jeu (§4)
│       src/commonTest/kotlin/lg/core/   # tests unitaires + fuzz
│       src/iosMain/kotlin/lg/core/IosExports.kt
├── androidApp/
│   ├── build.gradle.kts, proguard-rules.pro
│   └── src/main/{AndroidManifest.xml, kotlin/lg/android/, res/}
│       src/androidTest/kotlin/lg/android/SmokeTest.kt
├── iosApp/
│   ├── LightweightGames.xcodeproj
│   ├── LightweightGames/  (Swift, Info.plist, Localizable.xcstrings, Products.storekit, PrivacyInfo.xcprivacy)
│   ├── LightweightGamesTests/
│   └── Frameworks/        (gitignoré, XCFramework prébuilt en CI)
├── tools/  footprint.sh, ios_size.sh, wordlist/build_wordlist.py, icons/gen_icon.py
├── docs/   ARCHITECTURE.md, QA_CHECKLIST.md, STORE_LISTING.md
└── .github/workflows/ci.yml
```

### Fichiers de build clés

- `gradle/libs.versions.toml` : versions kotlin, agp, billing, sdk ; plugins `org.jetbrains.kotlin.multiplatform`, `com.android.kotlin.multiplatform.library` (repli `com.android.library`), `com.android.application`, `org.jetbrains.kotlin.android`.
- `gradle.properties` : `android.enableR8.fullMode=true`, `kotlin.native.ignoreDisabledTargets=true` (build Linux sans macOS).
- `core/build.gradle.kts` : cibles `androidLibrary`, `iosArm64`, `iosSimulatorArm64` ; `XCFramework("GamesCore")` statique, `-Xbinary=smallBinary=true` (expérimental, à valider par mesure) ; `explicitApi()` ; aucune dépendance en commonMain ; `kotlin-test` en commonTest.
- `androidApp/build.gradle.kts` : `isMinifyEnabled`, `isShrinkResources`, `proguard-android-optimize.txt`, `localeFilters(en, fr)`, `debugSymbolLevel = NONE`, `buildConfig = false`, exclusions META-INF/kotlin, splits AAB ; dépendances : `project(":core")` + billing.
- Intégration Xcode : build phase « Run Script » avant Compile Sources qui lance `./gradlew :core:embedAndSignAppleFrameworkForXcode` (sauté si `LG_USE_PREBUILT=1` en CI) ; `IPHONEOS_DEPLOYMENT_TARGET 15.0`, `-Osize`, strip, dead code stripping, pas de storyboard, `UILaunchScreen` couleur unie.

---

## 4. Module `core` (commonMain, package `lg.core`)

### Packages

```
Game.kt, GameMeta.kt, GameRegistry.kt, GameStatus.kt
input/   PointerPhase, Gesture, GestureDetector
scene/   Scene, Palette, StringKey, Align, SoundId
util/    Rng (SplitMix64), Layout, IntGrid, Clock, KvCodec
store/   KeyValueStore, Settings, Scores, Entitlement, Obfuscator
session/ GameSession, Overlay
ai/      Minimax (M3)
games/   snake/, g2048/, breakout/, sequence/, pairs/, sliding/, tictactoe/, paddles/,
         fourinrow/, bird/, mines/, hangman/ (+ WordsFr.kt, WordsEn.kt), reversi/, sudoku/, nonogram/
```

### Contrat `Game`

```kotlin
public interface Game {
    public val meta: GameMeta
    public val status: GameStatus          // Idle, Running, Paused, Over(winner: Int)
    public val score: Int
    public val wantsFrames: Boolean        // true = boucle active ; false = redessin sur entrée seulement
    public fun reset(seed: Long, players: Int = 1)
    public fun resize(width: Float, height: Float)               // points logiques (dp / pt)
    public fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float)  // brut, multi-touch
    public fun onGesture(gesture: Gesture)                        // TAP, SWIPE_*, LONG_PRESS
    public fun update(dtMs: Int): Boolean                         // true si la scène a changé
    public fun render(scene: Scene)
    public fun snapshot(): String? = null                         // "k=v;k=v" pour les jeux reprenables
    public fun restore(data: String): Boolean = false
}
```

- `dtMs` borné à 50 ms par `GameSession` (pas de spirale de rattrapage après retour d'arrière-plan).
- `GameMeta(id, titleKey, tier FREE/UNLOCKED, players IntRange, orientation, complexity, resumable, factory, icon: (Scene, Float) -> Unit)`. L'icône de tuile est dessinée par le core.
- `GameRegistry.all` (gratuits d'abord), `byId`, `playable(unlocked)`.

### `Scene` : tampon SoA à capacité fixe, zéro allocation par frame

- `capacity = 1024` ; tableaux `kind: ByteArray`, `f: FloatArray` (6 floats/primitive), `color: IntArray` (index `Palette`, jamais ARGB), `key: IntArray` (ordinal `StringKey` ou valeur numérique), `label: Array<String?>`, `soundMask`, `haptic`.
- Primitives : `rect`, `circle`, `line`, `arc`, `text(StringKey)`, `number(Int)`, `label(String)`. Dépassement plafonné + compteur `dropped` vérifié par le fuzz.
- `Palette` : index (`BG, SURFACE, FG, FG_DIM, ACCENT, P1, P2, GRID, OK, DANGER, TILE_0..11`) mappés en ARGB par la plateforme selon thème clair/sombre.
- `StringKey` : enum, aucune traduction dans le core.

### Autres briques

- `GestureDetector(out)` : tap < 12 pt et < 250 ms, swipe ≥ 24 pt, long press ≥ 450 ms. Les deux flux (pointeur brut et gestes) sont toujours transmis au jeu.
- `GameSession(meta, scores, settings, clock)` : `start(players, seed)`, `resize`, `onPointer` (route overlay ou jeu), `update`, `render` (jeu + HUD score/best + overlay), `pause/resume`, `needsFrameLoop`, `persist/tryRestore`. Boutons overlay ≥ 64 pt.
- `KeyValueStore` et `Clock` : **interfaces injectées** (pas expect/actual) implémentées par SharedPreferences / UserDefaults.
- `Settings` (son, haptique), `Scores` (`best(id)`, `submit(id, score)`).
- `Entitlement(store, clock)` : `isUnlocked`, `grant(source, token)`, `revoke`, `markVerified`. Blob `ent.v1` = `Obfuscator.encode("1|grantedMs|crc32")` XOR clé dérivée d'un `install.id`. Objectif : empêcher l'édition triviale des prefs, pas du DRM.
- `Rng(seed)` SplitMix64 : `nextInt(bound)`, `nextFloat`, `shuffle(IntArray)`. Parties reproductibles en test.
- `Layout` : `fitSquare`, `splitTwoPlayers` (haut/bas en portrait, gauche/droite en paysage), cibles ≥ 48 pt. `IntGrid(cols, rows)` sur `IntArray` fixe.

---

## 5. Couche plateforme

### Android (`androidApp/src/main/kotlin/lg/android/`)

| Fichier | Rôle |
|---|---|
| `App.kt` | `Application` : instancie `PrefsStore`, `Settings`, `Scores`, `Entitlement`, `PlayBilling` (lazy) |
| `MainActivity.kt` | Catalogue : `ScrollView` + `LinearLayout` de `TileView` (2 colonnes portrait, 3-4 paysage), bouton réglages, bandeau « Débloquer tout » |
| `GameActivity.kt` | `EXTRA_GAME_ID`, crée `GameSession`, orientation selon `meta`, `onPause` → pause + persist, retour = pause puis quitter, `setSystemGestureExclusionRects` pour 2 joueurs, `configChanges` pour gérer la rotation par `resize` |
| `PaywallActivity.kt` | Icône, prix (`ProductDetails`), « Débloquer tout », « Restaurer », état hors ligne, barrière parentale (appui 3 s) |
| `SettingsDialog.kt` | `AlertDialog` framework : son, vibrations, restaurer, version |
| `ui/GameView.kt` | `View` custom, `Choreographer.FrameCallback` actif seulement si `needsFrameLoop`, `onTouchEvent` multi-pointeurs → dp → `session.onPointer` |
| `ui/CanvasRenderer.kt` | `Scene` → `Canvas`, `Paint`/`RectF` préalloués, police système |
| `ui/Palette.kt`, `ui/Strings.kt`, `ui/TileView.kt` | index → ARGB (uiMode), `StringKey` → `R.string` (IntArray par ordinal), tuile ≥ 120 dp avec cadenas |
| `audio/Beeper.kt`, `audio/Haptics.kt` | buffers PCM générés via `AudioTrack` statique ; `performHapticFeedback` |
| `storage/PrefsStore.kt`, `storage/SystemClock.kt` | `KeyValueStore` sur `SharedPreferences("lg")` |
| `billing/PlayBilling.kt` | voir §6 |

Manifest : `hardwareAccelerated`, `allowBackup="false"` (le blob d'entitlement ne migre pas sans vérification), thème `DeviceDefault.NoActionBar`, aucune permission. Seule ressource image : icône adaptive vectorielle. `values/strings.xml` (en) + `values-fr/strings.xml`.

### iOS (`iosApp/LightweightGames/`)

| Fichier | Rôle |
|---|---|
| `AppDelegate.swift` | Instancie `DefaultsStore`, `Settings`, `Scores`, `Entitlement`, `StoreManager` ; lance le listener `Transaction.updates` |
| `Catalog/CatalogViewController.swift`, `TileCell.swift` | `UICollectionView` compositional layout 2-4 colonnes, tuile dessinée via le renderer |
| `Game/GameViewController.swift` | héberge `GameView`, bouton fermer, orientations selon `meta`, `preferredScreenEdgesDeferringSystemGestures` pour 2 joueurs, `willResignActive` → pause + persist |
| `Game/GameView.swift` | `UIView`, `CADisplayLink` (60 max) `isPaused` quand `!needsFrameLoop`, `touches*` → id par `ObjectIdentifier(touch)` → `session.onPointer`, `isMultipleTouchEnabled` |
| `Game/CoreGraphicsRenderer.swift`, `Palette.swift`, `Strings.swift` | `Scene` → `CGContext`, `CGColor` précalculés, `UIFont.systemFont` en cache, couleurs dynamiques clair/sombre, `String(localized:)` via `.xcstrings` |
| `Store/StoreManager.swift`, `Store/PaywallViewController.swift` | voir §6 |
| `Settings/SettingsViewController.swift` | `UITableViewController` insetGrouped |
| `Storage/DefaultsStore.swift`, `SystemClock.swift` | conformes aux protocoles Kotlin exportés |
| `Audio/Beeper.swift`, `Haptics.swift` | `AVAudioEngine` + `AVAudioPCMBuffer` générés ; `UIImpactFeedbackGenerator` |

Le renderer lit `scene.f` via `KotlinFloatArray.get` ; si le profil est défavorable, exposer un `CPointer<FloatVar>` en `iosMain`.

### Mapping des entrées (commun)

- Coordonnées converties en points logiques avant `onPointer`.
- Deux joueurs : le jeu partitionne l'écran via `Layout.splitTwoPlayers` ; la plateforme transmet tous les pointeurs actifs avec des ids stables.
- Rotation : la plateforme rappelle `session.resize(w, h)`, jamais de reset.
- Retour d'arrière-plan : toujours en état `Paused` avec overlay « Reprendre », jamais de reprise automatique du temps réel.

---

## 6. Achat intégré « Débloquer tout »

- Produit non consommable, id identique sur les deux stores : `unlock_all`. `Entitlement.Source { PLAY, APPSTORE, NONE }`.
- **Règle d'or** : l'état local (`Entitlement.isUnlocked()`) est la **seule** chose qui décide si un jeu est verrouillé. La boutique n'est jamais consultée pour jouer, ni au lancement, ni à l'ouverture d'un jeu. Un acheteur joue à tout, hors ligne, en avion, avec un autre compte, sans internet pendant des mois : aucun cadenas ne réapparaît tant que l'état local dit « débloqué ». On accepte sans réserve le risque de piratage local ; le pire scénario est un achat inutilisable faute de réseau.
- **Boutique injoignable** : ne change rien pour un acheteur (rien n'est affiché, rien n'est vérifié). Pour un non-acheteur, les jeux payants gardent leur cadenas (état local « verrouillé ») et l'écran d'achat affiche le prix indisponible avec la notice « hors ligne », bouton d'achat grisé, écran toujours fermable.
- **Boutique de nouveau joignable** : vérification opportuniste et silencieuse (au plus une fois par 7 jours) qui ne peut que confirmer ou compléter l'état local (accorder un achat trouvé, marquer vérifié). Elle ne retire jamais l'accès. Seuls deux événements peuvent révoquer : un remboursement prouvé par la boutique (transaction révoquée sur iOS ; sur Android, réponse explicite « non possédé » lors d'une restauration demandée par l'utilisateur), ou l'utilisateur lui-même via « Restaurer les achats ».
- Barrière parentale simple avant tout appel d'achat.

### Android, `PlayBilling.kt` (Play Billing ≥ 8)

- `BillingClient` avec `enablePendingPurchases(...enableOneTimeProducts())`. Connexion **paresseuse** : ouverture du paywall, « Restaurer », ou revalidation silencieuse au plus une fois par 7 jours si déjà débloqué. Jamais au lancement.
- Paywall : `queryProductDetailsAsync` → prix ; erreur → état hors ligne, bouton grisé.
- Achat : `PURCHASED` → `acknowledgePurchase` → `grant(PLAY, token)` ; `PENDING` → message d'attente + `queryPurchasesAsync` au prochain lancement ; `ITEM_ALREADY_OWNED` → `queryPurchasesAsync` puis grant.
- Restaurer (geste explicite de l'utilisateur) : `queryPurchasesAsync(INAPP)` ; présent → grant (acquitter si besoin) ; **réponse vide avec succès** → `revoke()` (remboursement) ; erreur → conserver l'état local. La revalidation silencieuse utilise la même requête avec `allowRevoke = false` : elle accorde ou confirme, jamais ne révoque.
- Tests : testeurs de licence Play Console, piste interne, cartes de test approuvé / en attente / refusé.

### iOS, `StoreManager.swift` (StoreKit 2, iOS 15+)

- `Product.products(for: ["unlock_all"])` à l'ouverture du paywall ; échec → notice hors ligne.
- `product.purchase()` → `.verified(tx)` → `grant(APPSTORE, tx.id)` → `tx.finish()` ; `.pending` (Ask to Buy) → attente ; `.userCancelled` → rien.
- Listener `Transaction.updates` : transactions différées ; `revocationDate != nil` → `revoke()`.
- Au lancement (local, sans réseau) : `Transaction.currentEntitlements` → grant si présent ; **ne jamais révoquer sur absence** (cache vide hors ligne).
- Restaurer : `AppStore.sync()` puis `currentEntitlements`.
- Tests : `Products.storekit` (succès, Ask to Buy, échec, remboursement), sandbox, `SKTestSession` en XCTest.

---

## 7. Stratégie d'empreinte

### Android (cible < 10 Mo, attendu 1,5 à 3 Mo)

R8 full mode + `-repackageclasses ''` + `-allowaccessmodification`, aucun keep pour le core (pas de réflexion), shrink resources, locales en/fr, aucune dépendance AndroidX runtime, icône vectorielle seule, pas de BuildConfig. `tools/footprint.sh` : `apkanalyzer apk download-size` et `dex packages`, échec CI si téléchargement > 5 Mo.

### iOS (cible < 15 Mo, attendu 4 à 8 Mo après thinning)

Framework statique, `smallBinary`, zéro kotlinx, `explicitApi` + `@HiddenFromObjC` sur les types internes, `-Osize`, strip, pas de bitcode, icône seule dans le catalogue d'assets. `tools/ios_size.sh` : export avec thinning → App Thinning Size Report, échec CI si > 12 Mo. **Mesurer dès M0** (voir risques).

### Mémoire et batterie (les deux)

- `Scene` fixe, tableaux primitifs, aucune allocation dans `update`/`render` ; entités (briques, tuyaux, segments) en tableaux fixes avec index libre ; pas de `List<Any>` ni lambdas capturantes dans les boucles.
- `Paint`/`CGColor`/`UIFont` créés une fois. Aucun bitmap.
- Catalogue statique sans animation ; boucle de rendu uniquement en jeu temps réel et statut `Running` ; pas de wake lock ; aucun appel store au démarrage.

---

## 8. Tests

### commonTest (`core/src/commonTest/kotlin/lg/core/`)

- `util/RngTest`, `input/GestureDetectorTest`, `store/EntitlementTest` (round-trip obfuscation, blob corrompu → verrouillé, `FakeStore` mémoire), `store/ScoresTest`, `util/KvCodecTest`, `RegistryTest` (ids uniques, exactement 3 FREE, `icon` sans dépassement).
- Un `games/<jeu>/<Jeu>RulesTest` par jeu sur ses règles clés (Snake collisions et croissance ; 2048 pas de double fusion, détection de blocage ; Démineur premier tap sûr ; Sudoku grille valide et unique ; Taquin parité solvable ; Nonogramme solvable par le solveur ligne ; Reversi passe forcée).
- `fuzz/HeadlessPlayTest` : pour chaque jeu du registre, 20 seeds, `reset` + `resize` portrait puis paysage, 5 000 itérations mêlant `update(16)`, pointeurs et gestes aléatoires, `snapshot/restore` si reprenable. Assertions : aucune exception, `scene.dropped == 0`, score ≥ 0, `render` idempotent (même hash sans update).
- `fuzz/DeterminismTest` : même seed + mêmes entrées → même hash de scène.

### Plateformes

- Android `SmokeTest` (instrumentation) : 15 tuiles, ouverture de Snake, rotation, pause/resume, swipe injecté. `StringsCoverageTest` (JVM) : chaque `StringKey` mappée.
- iOS `SmokeTests.swift` : catalogue chargé, `GameViewController("snake")`, 60 updates. `StoreKitTests.swift` avec `SKTestSession` : achat → débloqué, remboursement → verrouillé.

### Checklist manuelle (`docs/QA_CHECKLIST.md`)

Mode avion complet (lancement, 3 jeux, paywall non bloquant) ; achat puis relance hors ligne ; désinstallation/réinstallation + Restaurer ; Ask to Buy et achat en attente ; remboursement → révocation ; rotation en cours de partie ; appel entrant ; 2 joueurs à deux doigts sans déclencher les gestes système ; thème sombre ; grandes polices ; aucun rafraîchissement sur le catalogue ; rapports de taille joints à chaque release.

---

## 9. Phases de livraison

### M0 : squelette + Serpent de bout en bout sur les deux plateformes

Livrable : app installable Android et iOS, catalogue à une tuile, Serpent jouable, score persisté, **rapport de taille iOS et Android produits**.
Fichiers : build racine + catalogue de versions + CI ; core `Game.kt`, `GameMeta.kt`, `GameRegistry.kt`, `input/*`, `scene/*`, `util/{Rng,Layout,IntGrid,Clock}.kt`, `store/{KeyValueStore,Scores,Settings}.kt`, `session/*`, `games/snake/SnakeGame.kt`, tests `RngTest`, `GestureDetectorTest`, `SnakeRulesTest`, `HeadlessPlayTest` ; Android `App`, `MainActivity`, `GameActivity`, `ui/*`, `storage/*`, manifest, strings en/fr, icône ; iOS projet Xcode, `AppDelegate`, `Catalog/*`, `Game/*`, `Storage/*`, `Localizable.xcstrings`, build phase Gradle ; `tools/footprint.sh`, `tools/icons/gen_icon.py`.

### M1 : 3 jeux gratuits + catalogue complet + persistance

Livrable : Serpent, 2048, Casse-briques ; grille complète avec tuiles verrouillées (cadenas) ; réglages son/haptique ; reprise de 2048.
Fichiers : `games/g2048/Game2048.kt`, `games/breakout/BreakoutGame.kt`, `util/KvCodec.kt`, tests ; `audio/*` et `SettingsDialog.kt` Android ; `Audio/*` et `Settings/*` iOS ; `docs/ARCHITECTURE.md`.

### M2 : achat intégré + flux jeux verrouillés

Livrable : achat, restauration, cache hors ligne, paywall avec état hors ligne, barrière parentale, révocation sur remboursement, testeurs de licence et config StoreKit.
Fichiers : `store/Entitlement.kt`, `store/Obfuscator.kt`, `EntitlementTest`, `billing/PlayBilling.kt`, `PaywallActivity.kt`, `Store/StoreManager.swift`, `Store/PaywallViewController.swift`, `Products.storekit`, `StoreKitTests.swift`, `docs/QA_CHECKLIST.md`.

### M3 : lot 1 des jeux débloqués (complexité S + Quatre en ligne)

Livrable : Séquence, Paires, Taquin, Morpion, Raquettes, Quatre en ligne ; `ai/Minimax.kt`.
Fichiers : un `games/<id>/<Jeu>Game.kt` + `RulesTest` par jeu, registre et strings mis à jour.

### M4 : lot 2 (complexité M et C)

Livrable : Oiseau, Démineur, Pendu, Reversi, Sudoku, Nonogramme.
Fichiers : idem + `hangman/Words{Fr,En}.kt` générés par `tools/wordlist/build_wordlist.py` (≈ 600 mots par langue, 5 à 9 lettres, sans accents, filtrés pour un public enfant, source à licence libre documentée), `sudoku/SudokuGenerator.kt` + `SudokuGeneratorTest` (unicité), `nonogram/LineSolver.kt` + `NonogramSolvableTest`.

### M5 : polish, fiches store, audit d'empreinte

Livrable : builds release signés, captures générées, textes de fiche, déclarations « aucune donnée collectée » (Data Safety Play, `PrivacyInfo.xcprivacy`), audit taille/mémoire/batterie, tests d'instrumentation et XCTest verts en CI.
Fichiers : `tools/ios_size.sh`, `tools/screenshots/`, `docs/STORE_LISTING.md`, `PrivacyInfo.xcprivacy`, `SmokeTest.kt`, `SmokeTests.swift`, réglages finaux ProGuard et Xcode Release.

---

## 10. Risques et points ouverts

- **Taille du binaire Kotlin/Native iOS** : stdlib + runtime pèsent typiquement 2 à 4 Mo même avec DCE. Mitigations dans l'ordre : framework statique, `smallBinary`, zéro kotlinx, surface exportée réduite (`explicitApi`, `@HiddenFromObjC`). Mesure obligatoire dès M0 ; si > 8 Mo, façade `CoreFacade` unique ; en dernier recours, port Swift du core.
- **Accès aux tableaux Kotlin depuis Swift** : `KotlinFloatArray.get` par élément à profiler en M0 ; solution `CPointer` en réserve.
- **Versions minimales imposées par les stores** : Google exige une version glissante de Play Billing (8 attendue pour août 2026) et targetSdk 36 ; Apple exige Xcode 26 / SDK iOS 26. Revérifier dans les consoles au moment de M2 et M5.
- **Manifeste de confidentialité iOS** : `PrivacyInfo.xcprivacy` obligatoire, déclarer `UserDefaults` avec la raison CA92.1.
- **Politiques famille** : Google Play, si le public « enfants » est déclaré, la politique Designed for Families s'applique (compatible : pas de pub, pas de collecte, IAP transparent). Apple : ne pas viser la catégorie Kids (contraintes IAP), classer 4+ et garder la barrière parentale.
- **Zéro pub implique zéro SDK tiers** : pas d'analytics ni de crash reporter ; s'appuyer sur Play Vitals et Xcode Organizer.
- **Marques déposées** : jamais « Tetris », « Flappy Bird », « Puissance 4 / Connect 4 », « Othello », « Simon », « Pong », « Picross », « Memory ». Titres retenus en §1. « 2048 » est un jeu original sous licence MIT, conservé.
- **Génération Sudoku / Nonogramme** sur appareils bas de gamme : borner à 200 ms ; sinon embarquer 50 grilles pré-générées par difficulté (quelques Ko).
- **Sans permission INTERNET** : confirmer en M2 que Play Billing fonctionne (communication via le Play Store par IPC, attendu OK) ; pour un lien vers la politique de confidentialité, un `Intent` navigateur suffit.
- **Ergonomie 2 joueurs** en portrait et paysage (zones inversées pour le joueur en face) à valider en test manuel M3.

---

## 11. Mode d'exécution (demandé par l'utilisateur)

- Implémentation **déléguée à des sous-agents Sonnet** (`model: "sonnet"`), un agent par lot de fichiers cohérent ; l'orchestrateur relit, lance les tests et corrige les écarts.
- Découpage prévu pour M0 : (a) build Gradle + core (interfaces, Scene, GestureDetector, Rng, Snake, tests) ; (b) app Android ; (c) app iOS. (b) et (c) démarrent une fois (a) compilé et testé.
- Environnement Linux : les cibles iOS se compilent sans macOS (`kotlin.native.ignoreDisabledTargets=true`), mais le projet Xcode et les XCTest ne peuvent pas être exécutés ici. Ils sont écrits et documentés ; leur validation se fera sur un Mac.
- Vérification à chaque jalon : `./gradlew :core:allTests` puis `./gradlew :androidApp:assembleRelease` (si le SDK Android est disponible sur la machine) et `tools/footprint.sh`.

## 12. Vérification de bout en bout

1. `./gradlew :core:allTests` : tests de règles, fuzz headless et déterminisme verts pour les 15 jeux.
2. `./gradlew :androidApp:assembleRelease && tools/footprint.sh` : APK release, taille de téléchargement sous le seuil.
3. `xcodebuild test -scheme LightweightGames` puis `tools/ios_size.sh` : XCTest verts, rapport de thinning sous le seuil.
4. Installation sur un appareil physique de chaque OS en **mode avion** : jouer aux 3 jeux gratuits, ouvrir le paywall, constater l'absence de blocage.
5. Achat avec un testeur de licence / sandbox, relance hors ligne, désinstallation et « Restaurer ».
6. Dérouler `docs/QA_CHECKLIST.md` avant chaque release.
