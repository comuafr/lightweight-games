# Lightweight Games

One small mobile app (Android + iOS) with 15 minimal, offline games: 3 free, 12 unlocked by a single non-consumable purchase. No ads, no tracking, no account, no network needed to play.

- `PLAN.md` — the approved plan: catalog, architecture, IAP, footprint strategy, milestones, risks.
- `docs/JOURNAL.md` — session-by-session history of decisions and results.
- `docs/ARCHITECTURE.md` — how the code is organised and how to add a game.
- `docs/QA_CHECKLIST.md`, `docs/BILLING_TESTING.md`, `docs/STORE_LISTING.md` — release material.

## Layout

```
core/        Kotlin Multiplatform: game rules, Scene primitives, GameSession, entitlement (178 tests)
androidApp/  Android app: Views + Canvas, Play Billing 8, no AndroidX UI (see androidApp/README.md)
iosApp/      iOS app: UIKit + CoreGraphics, StoreKit 2, XcodeGen spec (see iosApp/README.md)
preview/     Dev-only JVM renderer: every game to PNG, no device needed
tools/       env.sh, footprint.sh, ios_size.sh, icon and word-list generators
legacy/      The previous approach (one standalone Android app per game); kept for reference only
```

## Build

```sh
source tools/env.sh                      # JDK 21 + Android SDK paths (see tools/env.sh)
./gradlew :core:jvmTest                  # core tests, no Android SDK needed
./gradlew :androidApp:assembleRelease && tools/footprint.sh
./gradlew :preview:run                   # PNG previews into preview/out/
```

iOS needs a Mac: `cd iosApp && xcodegen generate`, then build in Xcode (`iosApp/README.md`).

## Tester sur un appareil (Samsung et iPhone)

Aucun émulateur n'est nécessaire : les deux téléphones se testent en direct. La checklist à dérouler ensuite est `docs/QA_CHECKLIST.md` (mode avion, rotation, deux joueurs, achat).

### Samsung (Android) depuis ce PC Linux

1. **Activer le débogage USB sur le téléphone** : Paramètres > À propos du téléphone > Informations sur le logiciel > appuyer 7 fois sur « Numéro de version ». Puis Paramètres > Options de développement > activer « Débogage USB ».
2. **Brancher le câble USB**, choisir « Transfert de fichiers » si demandé, puis accepter la fenêtre « Autoriser le débogage USB » sur le téléphone (cocher « Toujours autoriser »).
3. **Vérifier la connexion** :
   ```sh
   source tools/env.sh
   adb devices          # doit lister le téléphone avec l'état "device" (pas "unauthorized")
   ```
4. **Construire et installer** :
   ```sh
   ./gradlew :androidApp:assembleRelease
   adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk
   ```
   L'APK release est signé avec la clé de debug tant qu'aucun `androidApp/keystore.properties` n'existe : il s'installe normalement, Play Protect peut afficher un avertissement « application inconnue », accepter.
5. **Lancer « Light Games »** depuis le tiroir d'applications. Pour voir les textes en français, la langue du téléphone doit être le français.
6. **Voir les plantages éventuels** pendant le test :
   ```sh
   adb logcat -s AndroidRuntime:E
   ```
7. **Tests d'instrumentation** (catalogue, Snake, rotation, écran de déblocage) :
   ```sh
   ./gradlew :androidApp:connectedDebugAndroidTest
   ```
8. **Sans câble** (Android 11+) : Options de développement > Débogage sans fil > « Associer l'appareil avec un code », puis `adb pair IP:PORT` et `adb connect IP:PORT`.

**Achat sur Samsung** : l'écran de déblocage affichera « hors ligne / indisponible » tant que l'application n'est pas publiée sur une piste de test interne de la Play Console avec le même identifiant `fr.regisp.lightgames` et la même signature. Le flux complet (prix, achat, restauration, remboursement) se teste ensuite avec un compte testeur de licence : voir `docs/BILLING_TESTING.md`. Tout le reste (jeux, réglages, hors ligne, barrière parentale) se teste sans Play Console.

### iPhone (iOS) : un Mac est indispensable

Apple n'autorise l'installation que depuis Xcode (ou TestFlight, qui demande un compte développeur payant). Un compte Apple gratuit suffit pour installer sur son propre iPhone pendant 7 jours renouvelables.

1. **Sur le Mac** : installer Xcode 26 (App Store), puis dans un terminal :
   ```sh
   brew install xcodegen temurin@21
   git clone git@github.com:comuafr/lightweight-games.git && cd lightweight-games
   cd iosApp && xcodegen generate && open LightweightGames.xcodeproj
   ```
2. **Signature** : dans Xcode, cible `LightweightGames` > Signing & Capabilities > cocher « Automatically manage signing » et choisir votre Apple ID comme Team (Xcode > Settings > Accounts pour l'ajouter). Si l'identifiant `fr.regisp.lightgames` est refusé, le changer dans `iosApp/project.yml` (clé `PRODUCT_BUNDLE_IDENTIFIER`) et relancer `xcodegen generate`.
3. **Sur l'iPhone** : brancher le câble, « Se fier à cet ordinateur », puis Réglages > Confidentialité et sécurité > Mode développeur > activer (redémarrage).
4. **Lancer** : sélectionner l'iPhone dans la barre d'outils Xcode, puis ⌘R. Le premier build lance Gradle (`embedAndSignAppleFrameworkForXcode`) et télécharge le compilateur Kotlin/Native : compter plusieurs minutes.
5. **Première ouverture** : si iOS refuse de lancer l'app, Réglages > Général > VPN et gestion de l'appareil > faire confiance au certificat de développeur.
6. **Premier build** : le code Swift n'a jamais été compilé sur Mac. La liste des points à vérifier en cas d'erreur (noms exportés par Kotlin, clé StoreKit du schéma, `CoreConfig.shared`) est dans `iosApp/README.md`, section « Things that may need fixing on first Mac build ».
7. **Achat sur iPhone** : le schéma utilise la configuration locale `iosApp/Products.storekit`, donc l'achat, la restauration, « Demander à acheter » et le remboursement se testent directement depuis Xcode (menu Debug > StoreKit) sans App Store Connect.
8. **Tests XCTest** : ⌘U dans Xcode. Rapport de taille : `tools/ios_size.sh`.

### iPhone sans Mac : compiler sur GitHub, installer depuis Linux

Le workflow `.github/workflows/ios.yml` tourne sur un Mac hébergé par GitHub. Il compile tout le Swift, exécute les XCTest dans le simulateur et publie un artefact `ios-unsigned-ipa` (application non signée). Lancement manuel :

```sh
gh workflow run ios.yml && gh run watch          # ~15 à 25 min ; sur un dépôt privé, les minutes macOS comptent 10x
gh run download --name ios-unsigned-ipa          # récupère LightweightGames-unsigned.ipa
```

Installer cet IPA sur l'iPhone impose une signature Apple. Deux voies, toutes deux sans Mac :

- **Compte Apple gratuit + sideloading depuis Linux** : un outil comme Sideloader (`github.com/Dadoum/Sideloader`) ou AltServer-Linux signe l'IPA avec votre Apple ID et l'installe par USB via libimobiledevice. Validité 7 jours, à refaire ensuite ; outils non officiels, à utiliser avec un Apple ID dédié de préférence. Dans ce mode, la configuration StoreKit locale n'est pas active : l'écran d'achat affichera « indisponible », tout le reste se teste.
- **Compte développeur Apple (99 $/an) + TestFlight** : le même workflow peut signer et envoyer le build sur TestFlight avec une clé API App Store Connect (à ajouter le moment venu) ; l'installation se fait alors depuis l'app TestFlight, sans câble, et les achats se testent en sandbox.

Un Mac reste plus confortable pour déboguer (points d'arrêt, Instruments, menu StoreKit), mais il n'est pas indispensable pour compiler ni pour tester sur l'appareil.
