# Mail-order catalogues

`catalogue(year)` returns the era's item IDs, labels and unit prices in cents. It uses economy prices for shared stock and indexes catalogue-only goods from their reference years. Restricted goods follow fictional period rules.

`placeOrder(items, buyer, address, year, day, method)` returns null for unavailable goods, invalid quantities, or incompatible payment, ordering or pickup options. Each line supplies an item ID and positive integer quantity. The buyer supplies the name used on the order and a payment method. The address supplies a display label, destination and merchant comms places, and pickup kind. Day numbers use the caller's game clock.

Mail ordering includes outbound letter time. Processing takes 17 days before 1940, five until 1980 and one thereafter. Domestic parcels use railway transit time rounded up to days, with a minimum of one day. Overseas parcels use surface mail timing. Phone ordering opens in 1930 and online ordering in 1995. Cards are supported from 1960 and required online. House kits require station collection. Home delivery begins in 1930.

The quote includes goods totals, arrival day, pickup kind and the evidence record, including the given name, destination, payment method and order date. Wallet changes, post office pickup, parcel state and evidence ingestion are caller responsibilities.
