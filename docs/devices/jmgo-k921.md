# JmGO K921 projector (坚果投影仪, MediaTek m7642)

Configured on 2026-10-24. The device is the family's JMGO projector (DHCP address, was
`192.168.77.13`, now `192.168.77.186` — scan the subnet for port 5555 when it moves; see
`local/TVS.md`); the owner enabled network ADB before the work. These observations apply to
this unit's firmware, not every JMGO projector.

Firmware properties read from the tested device:

- Android 9 / API 28, MediaTek board `m7642_JmGO_K921`, `ro.product.manufacturer=MediaTek`,
  model `JmGO`.
- `ro.adb.secure=0`: ADB connections need no confirmation on the TV.
- Network ADB runs through `persist.adb.tcp.port=5555`, independent of the global
  `adb_enabled` setting (which stays 0).
- 1920×1080 @ 320dpi — the same geometry as MiTV-MFTR0, so the 960×540 canvas density is 2.
- `ro.config.low_ram=true` (the device actually has ~3.5GB): like Xiaomi MiTV-MFTP0, Android
  refuses to bind the notification listener, so revival after a kill goes through the sync
  adapter (guard/RevivalSync.kt), 30–60 s later.

The unit runs a locally built standard APK: upstream release + the shortcut workaround below
(v1.22 + fix first, then rebased onto v1.47, versionCode 55). It is debug-signed — until the
workaround lands upstream, official releases cannot overwrite it (uninstall first, then reinstall;
the secure-settings state survives an uninstall).

## Installation and HOME

- `adb install` of the standard build works; no TCL-style package verifier.
- `cmd package set-home-activity` (Android 9 has no role service) reports Aerial as the home, and
  `resolve-activity -c HOME` agrees — but the remote's HOME key ignores all of it:

  - `PhoneWindowManager` (system_server) is patched to route HOME by the `persist.jmgo.home`
    prop, which only takes `launcher` or `arwen` (two JMGO UIs). `startHomeActivityLocked` then
    calls a JMGO-added `setDefaultLauncher()`, which clears every other package's HOME preferred
    activity and re-pins the launcher from `/system/etc/jmgodefault.dat`
    (`default_launcher = com.jmgo.launcher`; read-only without root). Pressing HOME anywhere
    therefore opens the JMGO launcher, never Aerial.
  - The takeover has to come from Home Guard. Standard installs can stop here (install.sh exits
    at "Aerial is the home screen"); on this firmware the guard must be enabled as well.

## The frozen accessibility setting and the shortcut workaround

JMGO patches `SettingsProvider` to refuse **every** write to
`enabled_accessibility_services` — shell, Aerial, and system_server all get
`E SettingsProvider: mutateSecureSetting disable modify enabled_accessibility_services: <pkg>`
and the value stays frozen at `com.jmgo.hippo/...JmgoKeyAccessibilityService`. The guard can
therefore never be enabled through the setting, and it cannot persist across reboots.

What works: the accessibility shortcut's enable path runs inside system_server and is past that
refusal. `AccessibilityManagerService.performAccessibilityShortcut()` (hidden API, transaction 15
on the `accessibility` service) toggles `accessibility_shortcut_target_service`, and
`enableAccessibilityServiceLocked()` adds it to the running set even though the setting write is
refused. The service binds and stays bound until reboot.

GuardSetup.enableThroughShortcut() does this from the app:

1. Writes `accessibility_shortcut_target_service` (a different key — this firmware still lets
   apps write it) and calls the hidden `performAccessibilityShortcut()` via reflection.
2. Android 9's hidden-API enforcement blocks the plain reflective lookup
   (NoSuchMethodException), so the lookup goes through meta-reflection: `Class.getDeclaredMethod`
   fetched from `Class.class` (public API), which P exempts. Fixed on Android 10, which does not
   matter — this path only exists for Android 9 firmwares like this one.
3. `guardBound()` counts the services Android is actually running, since the setting never shows
   the guard: without it every start would toggle the running guard straight back off. The
   shortcut call itself is rate-limited to one per 15 s (onStart and the sync adapter fire close
   together).

Verified on the device: Aerial 1.22 + this fix, guard bound, `filtering keys` logged, HOME key
brings Aerial back, and an explicit `am start` of the JMGO launcher is pulled back to Aerial
within seconds. `adb shell service call accessibility 15` performs the same toggle from ADB
(after `settings put secure accessibility_shortcut_target_service ...`) — a one-line manual
repair if the guard is ever stuck off.

## Reboot persistence

Verified on 2026-10-25 with a real power cycle (the unit had fully powered off): at boot,
BootReceiver ran, `enableThroughShortcut` bound the guard on its own
("the firmware refuses writes ...; bound the guard through the accessibility shortcut" in the
boot log), and Aerial took the foreground after the usual few-second flash of the JMGO launcher.
The same flow re-bound the guard again when the APK was updated over ADB. If HOME ever stays on
the JMGO launcher: `TV=<ip> tools/install.sh repair` will not fix this firmware by itself (it
restarts Aerial, whose ensureEnabled does the real work — give it a minute), or use the
`service call` line above.

## Standby and power (projector-specific)

- Remote-off ("standby") keeps the SoC and network ADB alive: `jmgo.system.power.state=3`,
  `mWakefulness=Awake`, screen captures come back black. `input keyevent KEYCODE_POWER` wakes it
  (state 3 → 0). Aerial keeps running; its screen shows on wake.
- With no input the unit powers off completely (auto power-off, default on projectors) and drops
  off the network — no ping, no ADB. This is normal for this projector and not caused by Aerial.

## Settings changed on this unit (restore via local/TVS.md notes)

- `screensaver_components` → Aerial's dream (was `com.jmgo.os.dream/...JGDreamService`); the
  unit's own screensaver setting `screensaver_enabled=1` triggered install.sh's swap.
- `accessibility_shortcut_enabled=1` (was unset/default) and
  `accessibility_shortcut_target_service` → the guard (both set over ADB; the app maintains the
  target itself).
- AppOps `android:bind_accessibility_service` → allow for Aerial (diagnostic during the
  investigation; the default mode is already allowed, keeping it explicit does no harm).
- The stock `enabled_accessibility_services` value is untouched — the firmware refuses writes, so
  `com.jmgo.hippo` was never removed and needs no restore.
