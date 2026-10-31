# ModShift

Update your Minecraft Java Edition mods, or move a whole mod folder to another Minecraft version, in one click. ModShift runs entirely in your browser. No install, no account.

**Use it here:** https://tanto81pro.github.io/Mod-Shift/

## What it does

Drop your `.jar` files (or your whole `mods` / `.minecraft` folder) onto the page and pick one of three actions:

- **Update**: stay on your current Minecraft version and get the newest build of every mod.
- **Set version**: move to another Minecraft version. Mods that already support it are left alone.
- **Set version + update**: move to another version and get each mod's newest build for it.

You get a ZIP (or a folder, in Chrome and Edge) with a ready-to-use `mods` folder and a report.

## Features

- **Fingerprint matching** against Modrinth (and CurseForge, with your own API key) to identify each mod, with a name-based fallback.
- **Loader switching** between Fabric, NeoForge, Forge and Quilt.
- **Dependencies** are resolved and added automatically; libraries nobody needs any more are flagged.
- **Conflict warnings** for mods that are marked as incompatible with each other.
- **"Which version can I move to?"** table showing how many of your mods exist on each Minecraft version.
- **Changelogs** for every update, with a one-click translation.
- **Suggestions**: optional add-ons for your mods and popular mods similar to your pack.
- **Search and add** mods from Modrinth without downloading jars yourself.
- **Safety checks**: broken files, wrong-loader files and the required Java and loader versions.
- **Keeps your settings**: drop your whole `.minecraft` folder and `config` plus `options.txt` are carried over.
- Release / beta / alpha channel choice, English and Turkish interface, light and dark theme.

## Privacy

Your mod files never leave your computer. Only their fingerprints (hashes) are sent to Modrinth and CurseForge to identify them. Files are downloaded only from the official Modrinth and CurseForge servers and every download is hash-checked.

## Respecting mod authors

ModShift never re-hosts files. Every download comes from the official CDN, so it counts for the author. Mods whose authors disabled third-party downloads are linked to their CurseForge page instead.

## CurseForge support

CurseForge requires an API key. Paste yours under **Settings**; it is stored only in your browser. If CurseForge blocks browser requests, the Settings panel includes a small Cloudflare Worker you can deploy as a proxy.

## Run it locally

Download `index.html` and open it in any modern browser. That's it.

## License

MIT
