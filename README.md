# SIM Emulator — LSPosed module

A production-quality LSPosed/Xposed module for **rooted devices** that makes apps
believe a physical (or embedded) SIM is present when none is inserted. Built for
**application testing and development** — QA of carrier-gated flows, onboarding
screens, dual-SIM UIs, eSIM logic, etc. — without a live SIM.

- **Targets:** Android 10–16 (minSdk 29, compileSdk/targetSdk 34)
- **Framework:** LSPosed (Xposed API 82). No system-partition changes; the APK is
  a normal user app that LSPosed injects into scoped processes.
- **Languages:** Kotlin (hooks + UI), interoperable with Java.
- **No Magisk module, no `/system` edits, no vbmeta patching.** Only LSPosed.

> Scope note: this spoofs values returned by Android telephony **Java APIs** inside
> the apps you choose. It does not create real radio connectivity and does not
> touch the baseband. It is a testing tool, meant to run against apps you own or
> are authorized to test.

---

## 1. Project layout

The requirements list `app/ core/ hooks/ settings/ utils/`. An LSPosed module must
ship as a **single APK** that both provides the hooks and hosts the settings UI, so
these are **packages inside one Gradle module** rather than separate modules:

```
SimEmulator/
├── settings.gradle, build.gradle, gradle.properties     # Gradle project
└── app/
    ├── build.gradle, proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/xposed_init                            # → HookEntry (registration)
        ├── res/…                                         # layouts, icon, strings
        └── java/com/simemu/spoof/
            ├── core/      # SimProfile, ProfileConfig, ConfigStore, ProfileGenerator, Constants
            ├── hooks/     # HookEntry + TelephonyManagerHook + SubscriptionManagerHook + BinderHook + SubscriptionInfoFactory
            ├── settings/  # MainActivity, ProfileEditActivity, ScopeActivity + adapters (the UI)
            └── utils/     # Reflect (reflection helpers), XLog
```

---

## 2. How it works (architecture)

Two processes are involved:

1. **Settings app** (`com.simemu.spoof`) — you configure profiles here. It writes
   the config as JSON into `SharedPreferences` (via `ConfigStore`).
2. **Hooked target app** — LSPosed loads `HookEntry` into it. On `Application.attach`
   the hook obtains a `Context`, reads the config by calling the module's
   **`ConfigProvider`** over binder, then rewrites telephony getter return values
   *inside that app's own process*.

```
[Settings UI] --writes JSON--> SharedPreferences
                                      │  (same uid, no SELinux issue)
                                      ▼
                              ConfigProvider (in module process)
                                      │  binder call: resolver.call(uri,"get")
                                      ▼
[Target app] <== HookEntry ==> TelephonyManager / SubscriptionManager getters
```

Because the hook runs inside the target, **no special permission is needed** to
alter what that app sees — that's why `getSubscriberId()` returns our IMSI even
though the platform would normally throw a permission error. The master switch and
the per-app allow-list are both checked in `HookEntry` before any hook is installed.

### Config sharing detail — why a ContentProvider, not XSharedPreferences
The obvious approach is `XSharedPreferences` (the hook reads the module's prefs
file). **This does not work on Android 10+** and was proven not to on-device:

- Every app's files carry **per-app SELinux categories** (observed:
  `app_data_file:s0:c100,c257,c512,c768`). A hook running inside a *different* app
  has different categories, so the kernel denies the read even with world-readable
  (`0644`) DAC bits.
- Modern **LSPosed also removed** `XSharedPreferences.makeWorldReadable()`, so the
  classic pattern throws `NoSuchMethodError` outright.

A `ContentProvider` sidesteps both: `untrusted_app → provider` binder calls are
always allowed by SELinux, and the provider runs in the module's own process where
it reads its own prefs with no cross-app barrier. One catch — **Android 11+ package
visibility** hides the provider's authority from target apps (`Unknown authority`),
so the module declares `android:forceQueryable="true"` and is installed
`--force-queryable`, making it resolvable from any app. (A sideloaded testing module
can always do this.)

---

## 3. Every hook, and why it's needed

### 3.1 `TelephonyManager` (`hooks/TelephonyManagerHook.kt`)
This is the API the vast majority of apps use. Each getter is bound across **all
overloads by name** (see `utils/Reflect.hookAllByName`) because these methods gained
`slotIndex` / `subId` variants over API 29→34, and we want to cover them all. We set
the result in `beforeHookedMethod`, which **short-circuits the real platform call** —
important, because with no SIM the originals return `null` / `SIM_STATE_ABSENT` / throw.

| Method | Forced value | Why apps read it |
|---|---|---|
| `getSimState()` / `getSimState(slot)` | `SIM_STATE_READY` (5) | The master gate. Apps refuse all telephony behavior unless the SIM reports READY. |
| `hasIccCard()` | `true` | Simplest "is a card present?" check. |
| `getSimCardState()` | `SIM_STATE_READY` | eUICC-level card-present check on newer APIs. |
| `getSubscriberId()` | IMSI | Uniquely identifies the subscriber; used for provisioning / anti-fraud. |
| `getSimSerialNumber()` | ICCID | The serial printed on the SIM; carrier apps key on it. |
| `getLine1Number()` | Phone number (MSISDN) | Pre-fills the user's own number. |
| `getSimOperator()` | MCC+MNC | Numeric home operator; drives CarrierConfig lookup. |
| `getSimOperatorName()` | Carrier name (SPN) | Human-readable home carrier shown in UIs. |
| `getSimCountryIso()` | Country ISO | Region logic, number formatting. |
| `getNetworkOperator()` | MCC+MNC | Numeric **serving** network. |
| `getNetworkOperatorName()` | Carrier name | Serving network display name. |
| `getNetworkCountryIso()` | Country ISO | Serving network country. |
| `getPhoneType()` | 1 (GSM) | Apps branch GSM vs CDMA on this. |
| `getCarrierId()` / `getSimCarrierId()` | Carrier ID | Android's canonical carrier id; gates VoLTE/RCS/CarrierConfig. |
| `getCarrierIdFromSimMccMnc()` | Carrier ID | Resolves a carrier id from MCC/MNC alone. |
| `getSimCarrierIdName()` | Carrier name | Display name backing the carrier id. |

For subscriber-scoped getters we inspect the `subId`/`slot` argument and return the
matching profile, so **dual-SIM** apps read correct per-slot values.

### 3.2 `SubscriptionManager` (`hooks/SubscriptionManagerHook.kt`)
The modern multi-SIM source of truth. Apps enumerate active subscriptions and pick a
default sub for calls/SMS/data here. With no SIM these return empty / `-1`, so we
synthesize **one subscription per profile** — which is exactly what yields
**dual-SIM, multi-profile, and eSIM** support.

| Method | Forced value | Why |
|---|---|---|
| `getActiveSubscriptionInfoList()` | list of fabricated `SubscriptionInfo` | The primary enumeration path. |
| `getActiveSubscriptionInfoCount()` | profile count | Apps check before iterating. |
| `getAllSubscriptionInfoList()` / `getAvailableSubscriptionInfoList()` | same list | Alternate enumerations. |
| `getActiveSubscriptionInfo(subId)` | matching `SubscriptionInfo` | Look up one sub by id. |
| `getActiveSubscriptionInfoForSimSlotIndex(slot)` | matching `SubscriptionInfo` | Look up by physical slot. |
| `getActiveSubscriptionIdList()` / `getSubscriptionIds()` | int[] of subIds | Raw id arrays. |
| `getDefaultSubscriptionId()` | configured default | System default sub. |
| `getDefaultDataSubscriptionId()` | configured default | Which sub carries data. |
| `getDefaultVoiceSubscriptionId()` | configured default | Which sub places calls. |
| `getDefaultSmsSubscriptionId()` | configured default | Which sub sends SMS (SMS apps read this). |
| `isActiveSubscriptionId(subId)` | true for our ids | Validity check. |

### 3.3 `SubscriptionInfo` fabrication (`hooks/SubscriptionInfoFactory.kt`)
`SubscriptionInfo` has **no public constructor**, and its private constructor changed
repeatedly across Android 10→16 (`mcc`/`mnc` went `int`→`String`; `mCarrierId`,
`mProfileClass`, card-id fields were added). Matching a constructor positionally is
fragile. Instead we:

1. **Allocate the object without any constructor** via `sun.misc.Unsafe.allocateInstance`.
2. **Set only the private fields we know, by name** (`mId`, `mIccId`, `mSimSlotIndex`,
   `mDisplayName`, `mCarrierName`, `mNumber`, `mCountryIso`, `mIsEmbedded`,
   `mCarrierId`, and both `mMcc`/`mMccString` variants…), silently skipping fields
   absent on a given API level.

This one code path works across all supported versions. `mIsEmbedded = true` marks a
profile as an **eSIM**.

### 3.4 Advanced: Binder-level interception (`hooks/BinderHook.kt`)
Some apps and obfuscated SDKs **bypass `TelephonyManager`** and call the AIDL
interfaces returned by `ServiceManager.getService()` directly. Inside the app process
those are `…$Stub$Proxy` classes. We reflectively locate and hook them as a second
safety net:

- **`IPhoneSubInfo$Stub$Proxy`** — `getSubscriberId`, `getIccSerialNumber`,
  `getLine1Number` (+ `…ForSubscriber` variants) → IMSI / ICCID / number.
- **`ITelephony$Stub$Proxy`** — `getSimState`, `getSimStateForSlotIndex` → READY (5).
- **`ISub$Stub$Proxy`** — `getDefaultSubId` / `…DataSubId` / `…VoiceSubId` / `…SmsSubId`.

Everything here is best-effort (`classOrNull`, hook-by-name, no-op if absent) because
these interfaces are internal and drift between versions.

### 3.5 Hidden-API handling
Android restricts non-SDK interface access on API 28+. `HookEntry` calls
`HiddenApiBypass.addHiddenApiExemptions("L")` on load, which is what lets us touch
`SubscriptionInfo`'s private fields and the internal telephony proxies.

---

## 4. Settings app (the UI)

`settings/MainActivity.kt`:
- **Enable/Disable** master toggle (`switchMaster`).
- **Profile list** — each row is one emulated SIM (tap to edit).
- **Add profile** — blank editor.
- **Random profile** — `core/ProfileGenerator` builds an internally-consistent SIM
  (IMSI begins with MCC+MNC, ICCID carries a valid Luhn check digit, real carrier
  MCC/MNC pairs) so validation apps accept it.
- **Per-app scope** (`ScopeActivity`) — the module's own allow-list; only apps toggled
  on get spoofed.
- **Import / Export** — JSON via the Storage Access Framework.

`settings/ProfileEditActivity.kt` edits every spoofable field: Carrier Name, Carrier
ID, MCC, MNC, Country ISO, Phone Number, ICCID/SIM Serial, IMSI, Subscription ID, Slot
Index, Phone Type, and the **eSIM** switch. Operator Numeric is derived (`MCC+MNC`).

### JSON config format
```json
{
  "profiles": [
    {
      "id": "…", "label": "T-Mobile", "carrierName": "T-Mobile", "carrierId": 1,
      "mcc": "310", "mnc": "260", "countryIso": "us",
      "phoneNumber": "+15555550100",
      "iccid": "8910390000005555010", "imsi": "310260000005555",
      "simSerial": "8910390000005555010",
      "subscriptionId": 1, "slotIndex": 0, "isEmbedded": false, "phoneType": 1
    }
  ],
  "scope": { "com.example.carrierapp": true },
  "defaultVoiceSubId": 1, "defaultDataSubId": 1, "defaultSmsSubId": 1
}
```
Two profiles with `slotIndex` 0 and 1 = **dual SIM**. Set `isEmbedded: true` for eSIM.

---

## 5. Build & install

This project builds **fully offline**. The Gradle wrapper is included, and the Xposed
API is provided by a small compile-only stub in `app/libs/xposed-api-82-stub.jar`
(exposes only the API symbols this module uses; never packaged into the APK). This
avoids depending on `api.xposed.info`, which is frequently unreachable. If that host
is up and you prefer the canonical artifact, swap the `compileOnly` line in
`app/build.gradle` back to `de.robv.android.xposed:api:82` — behaviour is identical.

Verified: builds green with Gradle 8.7 / JDK 17 / Android SDK 34, producing
`app/build/outputs/apk/debug/app-debug.apk`.

```bash
# from SimEmulator/
./gradlew assembleDebug

# --force-queryable is REQUIRED so hooked apps can resolve the config provider
# on Android 11+ (see the architecture note above). -g grants runtime perms.
adb install --force-queryable -r -g app/build/outputs/apk/debug/app-debug.apk

# optional: the on-device verifier app used below
./gradlew :demo:assembleDebug
adb install -r -g demo/build/outputs/apk/debug/demo-debug.apk
```

Then:
1. Open **LSPosed → Modules**, enable **SIM Emulator**.
2. In its **scope**, tick the apps you want to test (CPU-Z, Device Info HW, your app…).
3. **Force-stop** those apps (LSPosed hooks apply on next process start).
4. Open **SIM Emulator**, turn the master switch **on**, add/generate a profile, and
   enable the same apps under **Per-app scope**.
5. Relaunch the target apps.

---

## 6. Testing / verification

Because the hook only affects **scoped** apps, verify inside apps you've scoped:

- **CPU-Z** → *Device* tab: confirm carrier / network operator show your spoofed
  values instead of "No SIM".
- **Device Info HW** → *SIM* section: ICCID, IMSI, MCC/MNC, operator name should match
  your profile. Add a second profile (slot 1) to confirm the dual-SIM rows appear.
- **Carrier test apps** (any app that gates on `getSimState`/CarrierConfig): they
  should proceed past the "insert SIM" wall.
- **Custom demo app** (below): the most direct check.

### Bundled demo verifier (`:demo` module)
The project ships a `demo/` app (`com.simemu.demo`, "SIM Test Demo") that reads every
hooked value and prints it to screen **and** to logcat under tag `SIMTEST`. Scope it
in both LSPosed and the module, then:

```bash
adb shell am force-stop com.simemu.demo   # hooks apply on next start
adb shell am start -n com.simemu.demo/.DemoActivity
adb logcat -s SIMTEST                      # watch the values
```

Watch the **LSPosed log** to confirm activation:
```bash
adb logcat | grep -i SimEmulator
# → SimEmulator: Activating SIM emulation for com.simemu.demo (1 profile(s))
# → SimEmulator: TelephonyManager hooks installed ...
# → SimEmulator: SubscriptionManager hooks installed (1 sub(s))
```

### Verified on real hardware
Confirmed end-to-end on a **Pixel 7, Android 16 (SDK 36), rooted, zygisk LSPosed**,
against the `:demo` app configured with a T-Mobile test profile (mcc 310 / mnc 260,
subId 1). The device had a *real Airtel SIM* inserted, so this doubles as proof the
module **overrides** a present SIM:

| Getter | Real SIM (baseline) | After spoof |
|---|---|---|
| `getSimState` | 5 | 5 |
| `getSubscriberId` | *permission error* | `310260000005555` |
| `getSimSerialNumber` | *permission error* | `8910390000005555010` |
| `getLine1Number` | *(empty)* | `+15555550100` |
| `getSimOperator` | `40445` | `310260` |
| `getSimOperatorName` | `airtel` | `T-Mobile-FAKE` |
| `getSimCountryIso` | `in` | `us` |
| `getSimCarrierIdName` | `Airtel` | `T-Mobile-FAKE` |
| `SubscriptionInfo` | id=3, airtel, mcc=404 | id=1, T-Mobile-FAKE, mcc=310 mnc=260, iccid set |
| default sub/data/voice/sms Id | 3 | 1 |

The `getSubscriberId` / `getSimSerialNumber` rows are the key result: the platform
throws a permission error for third-party apps, and the hook makes them return the
profile value by short-circuiting before the permission check.

---

## 7. Limitations & notes

- Spoofs **Java-layer** getters only; it does not fabricate radio/RIL state, data
  connectivity, or SMS delivery. Apps that verify connectivity by actually sending
  traffic won't be fooled.
- `getLine1Number()` is increasingly restricted by the platform; some apps read the
  number via `SubscriptionInfo.number` instead — both are covered.
- Hooks apply on **next process start**; always force-stop the target after changing
  config or scope.
- If a value doesn't take, check that the app is enabled in **both** LSPosed scope
  (loads the hook) **and** the module's per-app scope (permits spoofing).
- The module **must** be installed `--force-queryable` (Android 11+), or hooked apps
  get `Unknown authority` reading the config provider and nothing spoofs.
- Some getters vary by build — e.g. on Android 16 `getCarrierId()` wasn't present as a
  hookable method (a harmless `no method named getCarrierId` log); `getSimCarrierId()`
  is hooked and covers the same need. The hooks bind by name and no-op when absent.
- The compile-only stub in `app/libs` must match LSPosed's **runtime signatures
  exactly** (return types included). Two were corrected during on-device testing:
  `XSharedPreferences.makeWorldReadable()` was removed by LSPosed, and `reload()`
  returns `void` — a mismatched stub throws `NoSuchMethodError` at load.
```
