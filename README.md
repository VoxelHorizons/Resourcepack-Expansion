# ResourcePack Expansion

Adds placeholders to get resource pack related stuff.

## Placeholder

* `%resourcepack_loaded%` - Returns `true` if the player is using the resource pack, `false` otherwise.
* `%resourcepack_status%` - Returns the current [resource pack status](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/event/player/PlayerResourcePackStatusEvent.Status.html) from player.
* `%resourcepack_id%` - Returns the id of the resource pack.
* `%resourcepack_url%` - Returns the current resource pack url.
* `%resourcepack_hash%` - Returns the SHA-1 digest of the server resource pack.
* `%resourcepack_prompt%` - Returns the custom prompt message to be shown when the server resource pack is required.
* `%resourcepack_required%` - Returns `true` if the server resource pack is required, `false` otherwise.
## Minecraft language translations

The expansion also resolves language overrides from the **actual server resource pack**
(`assets/minecraft/lang/<locale>.json`) using the requesting player's client language.

* `%resourcepack_locale%` — client's locale (such as `en_us` or `es_es`).
* `%resourcepack_translate_menu.back%` — value of `menu.back` in the player's selected language.
* `%resourcepack_translate_block.minecraft.stone%` — an overridden vanilla translation key, if supplied by the pack.

For example, with `assets/minecraft/lang/en_us.json` containing
`{"menu.back":"Back"}` and `assets/minecraft/lang/es_es.json`
containing `{"menu.back":"Volver"}`, the **same** placeholder returns the
appropriate translation for each player.

Translations load in the background from the resource-pack URL configured on
the server and refresh at most once every five minutes. On the first request,
or while a refresh runs, missing entries return their key unchanged.
For locally hosted or inaccessible pack URLs, place the **same actual pack ZIP**
at `plugins/PlaceholderAPI/resourcepack-translations.zip` as a local override,
rather than maintaining another set of language files. Restart the expansion
after changing this file, or allow the regular five-minute refresh.

If a locale is missing a key, lookup falls back to its base language, then
`en_us`, then the key itself. Resource packs typically only contain
**overrides**, so a built-in Minecraft translation that is not present in the
server pack cannot be returned by this placeholder. Other namespaces are not
loaded; lookup is intentionally limited to `assets/minecraft/lang`.

In DeluxeMenus you can use the placeholders in display names, lore, and other
text fields where PlaceholderAPI expansion is supported.
