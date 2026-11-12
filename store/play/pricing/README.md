# Prix de `unlock_all` par pays

`prices_2026-11-10.csv` donne, pour chacun des **174 pays** de la liste actuelle du Google Play, un prix qui a **le même pouvoir d'achat que 1,99 € en France**, **sans jamais dépasser 2,00 €** (converti au taux du jour) et **sans descendre sous 0,50 €**. Le prix est toutes taxes comprises, comme dans la console, et la **devise de facturation de chaque pays est conservée**.

## Règles (modifiables en tête de `tools/pricing/make_prices.py`)
- `BASE_EUR = 1.99` : prix en France. `CAP_EUR = 2.00` : plafond absolu, **après arrondi**, d'où quelques pays à 2,00 € équivalents. `FLOOR_EUR = 0.50` : plancher.
- Prix en euros d'un pays = `1,99 × min(1, indice)`, où l'indice est le niveau de prix du pays rapporté à la France (Banque mondiale : PPP de la consommation privée divisée par le taux de change officiel, même année pour le pays et la France). Les pays **plus riches que la France** (indice ≥ 1) sont donc **plafonnés** à 1,99 € converti (colonne `capped`).
- Conversion dans la devise de facturation au **taux de marché du 10 novembre 2026** (`open.er-api.com`), puis **arrondi à un prix « psychologique »** (x.x9, x.49/x.99, ou multiples de 5/10/50/100 selon l'ordre de grandeur) en restant entre le plancher et le plafond.
- **Territoires sans donnée** (colonne `estimated`) : Liechtenstein (comme la Suisse), Vatican (comme l'Italie), Monaco (comme la France), Gibraltar (comme le Royaume-Uni), les autres par la médiane de leur région et de leur niveau de revenu.

## Résultat
- 91 prix distincts (couples devise et montant). 31 pays plafonnés, 3 pays au plancher (Égypte, Libéria, Zimbabwe), 12 estimés.
- Équivalents en euros entre 0,50 € et 2,00 €. France : EUR 1,99. Aucun prix n'augmente de plus de 25 % ; 63 pays baissent de plus de 40 % (c'est l'effet de la parité de pouvoir d'achat sur les pays les moins chers).

## Colonnes
`group_id` (devise et prix : les pays du même groupe se saisissent d'un coup), `country`, `iso2`, `currency`, `current_price`, `new_price`, `change_pct`, `new_price_eur_equiv`, `ppp_index_vs_france`, `capped`, `floored`, `ppp_year` (année des données, 2021 pour les économies mesurées seulement par la dernière enquête), `estimated`, `vat_rate`, `new_price_net_of_vat` (information), `review_flag`.

`review_flag` : `volatile_currency` (monnaie qui bouge vite : à revoir chaque trimestre), `official_rate_distortion` (taux officiel éloigné du marché : indice peu fiable), `estimated_ppp`, `floored`.

## Appliquer ces prix dans la console
La console **n'importe plus de CSV** pour les produits ponctuels (supprimé le 19 mai 2025) et les modèles de prix ont disparu (27 octobre 2025). Il faut saisir les prix à la main, par groupes :
1. **Monetize with Play > Products > One-time products > `unlock_all`**, option d'achat `unlock-all`, puis **Set prices > Bulk edit pricing** (ou l'icône d'édition de chaque pays).
2. Trie le CSV par `group_id`. Pour chaque groupe, sélectionne les pays de la colonne `country`, saisis le `new_price` et valide.
3. Une fois fini, vérifie dans la liste des prix que la France est bien à 1,99 € et que tous les pays restent « Available ».
La console peut refuser un prix isolé s'il sort de ses minimums ou maximums pour la devise : corrige-le alors à la main (le plancher de 0,50 € évite normalement ce cas).

## Limites
- Les prix sont **une photo des taux du 10 novembre 2026** : un change qui bouge de plus de quelques pourcents fait sortir certains pays du plafond. Relance le script (`python3 tools/pricing/make_prices.py --refresh`) tous les trimestres, ou après une forte variation d'une monnaie.
- Les données de la Banque mondiale (licence CC BY 4.0) sont des estimations, parfois d'enquêtes anciennes ; le choix de la PPP de la consommation privée est une convention, pas la seule possible.
- Argentine, Égypte, Éthiopie, Liban, Myanmar, Nigeria, Venezuela, Zimbabwe : taux officiels très éloignés du marché ou économies instables, prix à relire.
- Sources en cache dans `data/` pour que le résultat soit reproductible ; `current_prices.psv` est la transcription de la liste collée le 10 novembre 2026.
