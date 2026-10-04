# NextWebUI

Standalone implementation of the [KernelSU WebUI](https://kernelsu.org/guide/module-webui.html) application.

Suitable for Magisk, KernelSU, and APatch.

## Highlights

- Calm, lightweight interface (plain Android Views, no heavy UI framework) with an optional wallpaper-color (Monet) mode on Android 12+
- Search, pinning (long-press a module) and a module counter
- Theme colors exported to WebUIs as CSS variables (`/internal/colors.css`) and safe-area insets (`/internal/insets.css`)
- Languages: English, العربية, 日本語, 简体中文, Polski

## JavaScript API additions

`ksu.moduleInfo()` now also returns `name`, `version`, `versionCode`, `author` and `description` from `module.prop`.

## Security notes

- The WebUI screen can only be opened from inside the app, and module ids are validated.
- Navigation away from the module's own origin opens in an external app instead of inside the root-privileged WebView.
- `cwd`, `env` values and `spawn` arguments are shell-quoted; callback names must be plain JS identifiers.
