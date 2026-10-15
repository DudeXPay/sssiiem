# Changes — same SIM for login and mobile data

Date: 2026-10-15

## Problem

Scoped apps showed: **“Please ensure you are using mobile data from the same SIM used for login.”**

The module already spoofed login identity (number, IMSI, ICCID). Mobile **data** still looked like it came from the **physical SIM**. The app compared those two and failed.

## What we built

Every scoped app now sees **only the SIMs you added**. Login number and mobile-data subscription are the **same** added SIM (for example number `123`). The real card is hidden from that app.

This is option B: no per-app SIM picker. All scoped apps share the active added SIMs.

### Debug APKs (built 2026-10-15)

| Module | APK |
|---|---|
| SIM Emulator | `app/build/outputs/apk/debug/app-debug.apk` |
| SIM Test Demo | `demo/build/outputs/apk/debug/demo-debug.apk` |

```bash
adb install --force-queryable -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g demo/build/outputs/apk/debug/demo-debug.apk
```

Then: enable the module in LSPosed, add a SIM profile, turn on Per-app scope for the target, **force-stop** that app, reopen it.

## What changed

### Config

- [`ProfileConfig.kt`](app/src/main/java/com/simemu/spoof/core/ProfileConfig.kt) — `dataSubId()`, `dataProfile()`, `syncDefaultsFromActive()`. Default data/voice/SMS always point at an active **added** SIM, not a leftover real-card id.
- [`ConfigStore.kt`](app/src/main/java/com/simemu/spoof/core/ConfigStore.kt) — syncs those defaults on every save.

### Hooks (the actual fix)

- [`SubscriptionManagerHook.kt`](app/src/main/java/com/simemu/spoof/hooks/SubscriptionManagerHook.kt) — hooks `getActiveDataSubscriptionId` (this was the main leak). Extra subscription lists and `getPhoneNumber` only return added SIMs.
- [`TelephonyManagerHook.kt`](app/src/main/java/com/simemu/spoof/hooks/TelephonyManagerHook.kt) — `getSubscriptionId`, `isDataEnabled`, `getDataState` = connected. `getSimState(slot)` / `hasIccCard(slot)` are READY only for added slots; extra physical slots look empty.
- [`BinderHook.kt`](app/src/main/java/com/simemu/spoof/hooks/BinderHook.kt) — ISub `getActiveDataSubscriptionId` / `getActiveDataSubId`; ITelephony data-enabled and slot state.
- **New** [`ConnectivityHook.kt`](app/src/main/java/com/simemu/spoof/hooks/ConnectivityHook.kt) — `NetworkCapabilities.getSubscriptionIds()`, `TelephonyNetworkSpecifier`, Settings.Global `multi_sim_data_call` all report the added data SIM.
- **New** [`TelephonyProviderHook.kt`](app/src/main/java/com/simemu/spoof/hooks/TelephonyProviderHook.kt) — `content://telephony/siminfo` returns only added profiles (number included). Real SIM rows are not listed.
- [`HookEntry.kt`](app/src/main/java/com/simemu/spoof/hooks/HookEntry.kt) — installs the new hooks.

### UI

- Home screen **Data + login SIM** card — shows the number scoped apps will use. If two added SIMs are active, tap to pick which one is data.
- Profile rows show a **DATA** badge on that SIM.
- Per-app scope hint: enabled apps see added SIMs only, not the physical card.

### Demo + README

- Demo prints `activeDataSubscriptionId`, `SubscriptionInfo.number`, and `telephony/siminfo` rows so you can confirm the real SIM is gone.
- [`README.md`](README.md) documents the login/data check and the radio/header-enrichment limit.

## Limit

This fixes **in-app** checks (Java APIs, connectivity subId, telephony DB).

It cannot change the baseband. If the **carrier server** injects the real MSISDN (header enrichment), the server still sees the physical SIM.
