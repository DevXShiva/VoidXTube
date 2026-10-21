# VoidTube settings audit: General, User interface, Search, Language, Accounts, Backup, About

Repo state: `main` @ 04869135 (2026-10-02). Research only, nothing edited. Labels are resolved from
`smarttubetv/src/stmobile/res/values/strings_mobile.xml` > `smarttubetv/src/main/res/values/strings.xml` >
`common/src/main/res/values/strings.xml` (the phone flavor's overrides are marked *[stmobile]*).

**Verdicts.** LIVE = a phone-executed path reads the value. DEAD = nothing the phone runs reads it.
PARTIAL = read, but only some of what the row promises happens, or it is broken (marked **BROKEN**).
UNCLEAR = read on the phone, but whether it does anything visible depends on runtime state I could not
settle from the code. KEYBOARD-ONLY = nothing landed here (the phone already hides the key-remapping
and exit-shortcut rows).

**Path legend** (file:line references use these short names)

| Short | File |
|---|---|
| GSP | common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/GeneralSettingsPresenter.java |
| MUSP | common/.../presenters/settings/MainUISettingsPresenter.java |
| BP | common/.../app/presenters/BrowsePresenter.java |
| SP | common/.../app/presenters/SplashPresenter.java |
| SS | common/.../app/presenters/service/SidebarService.java |
| VMP | common/.../presenters/dialogs/menu/VideoMenuPresenter.java |
| BMP | common/.../presenters/dialogs/menu/BaseMenuPresenter.java |
| SMP | common/.../presenters/dialogs/menu/SectionMenuPresenter.java |
| ADU | common/.../common/utils/AppDialogUtil.java |
| VSC | common/.../models/playback/controllers/VideoStateController.java |
| MA | common/.../common/misc/MotherActivity.java |
| MBA | smarttubetv/src/stmobile/java/com/newtube/mobile/ui/browse/MobileBrowseActivity.java |
| MPA | smarttubetv/src/stmobile/java/com/newtube/mobile/ui/playback/MobilePlaybackActivity.java |
| MMA | smarttubetv/src/stmobile/java/com/newtube/mobile/MobileMainApplication.java |
| MGO | MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/common/models/impl/mediagroup/MediaGroupOptions.kt |
| BMG | MediaServiceCore/youtubeapi/.../common/models/impl/mediagroup/BaseMediaGroup.kt |

**How the phone reaches these screens.** You tab > Settings row -> `MBA.openSettings()` (MBA:888) renders
`AppDataSourceManager.getSettingItems()` as buttons. On the phone the root list is: Accounts,
Language/Country, General, User interface, Player, Subtitles, Search, SponsorBlock, DeArrow,
Backup/Restore, About. (Remote control and Auto Frame Rate are hidden. The package is not in
`Utils.KNOWN_PACKAGES`, so About is `AboutSimpleSettingsPresenter`.) Each screen is an
`AppDialogPresenter` tree drawn by `MobileAppDialogActivity`/`DialogRowAdapter`, which does render row
descriptions. **Opening Settings never asks for a password** (`openSettings` has no check).

---

## 1. General (`GSP.show()`, GSP:75; screen title "General")

On the phone `show()` skips App exit shortcut, Screen dimming and Key remapping (GSP:85-92). What is left:

### 1a. "Boot to section" (radio), GSP:305

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does (summary text) | Side effects / notes |
|---|---|---|---|---|---|---|
| One radio per enabled section ("Home", "Subscriptions", "History", "Downloads", "Live", ...) plus one per pinned channel/playlist (by title). Shorts, Settings and disabled sections are hidden unless they are the current pick (GSP:316) | `SidebarService#getBootSectionId` / `#setBootSectionId` (SS:247/241) | `MediaGroup.TYPE_HOME` (SS:332) | `BP.refreshSections` BP:439 -> `getView().selectSection(mBootSectionIndex)` -> `MBA.selectSection` MBA:2058 (cold start, when no current section exists). Also `BP.prefetchBootSection` BP:1105 (Home is prefetched only when it is the boot section) | LIVE | The tab VoidTube opens on. | None. A stored Notifications boot is read as Home (SS:248). Kept in sidebar data, which is always per account (AppPrefs). |

### 1b. "Set-up sections" (checkbox list), GSP:100

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Home, Trending, Kids, Sports, Live, Gaming, News, Music, Channels, Subscriptions, History, Downloads, Blocked Channels, Playlists, My videos, Playback queue (Notifications is removed by `SS.getDefaultSections` on the phone; Shorts by GSP:115; Settings by GSP:109) | `SidebarService#isSectionPinned` / `BrowsePresenter#enableSection` -> `SidebarService#enableSection` (BP:759, SS:94) | All on except Notifications, Playback queue, Trending, Blocked Channels (SS:295-304). Downloads is switched on once for older installs (MMA `downloads_section`) | `BP.initPinnedSections` BP:366 -> `refreshSections` -> `MBA.addSection/removeSection` -> `MBA.rebuildBottomNav` MBA:1617 (Home/Subscriptions/History/Downloads become tabs, `selectNavSections` MBA:1733) and `MBA.rebuildYouRows` MBA:1040 (everything else is listed under You) | LIVE | Which feeds appear in the bottom bar and the You tab. | Applies at once. Turning off the section you are on jumps to the nearest one (BP:762). The bottom bar holds only those four fixed sections; the rest can only be listed in You. News has no mapping when the country is RU/BY (BP:327), so its row does nothing there. Blocked Channels switches itself on with the first blocked channel. Overlaps the long-press "Unpin from You". |

### 1c. "Context menu" (checkbox list), GSP:219, plus the "Position of …" reorder dialog (GSP:263)

The list shows every key in `MainUIData.getMenuItemsOrdered()` that has a name in `GSP.getMenuNames()`
(GSP:811): 40 stock items plus 3 provider items, in the user's menu order. **Checking** an item also
opens a "Position of <item>" radio screen (GSP:234 -> `showMenuItemOrderDialog`, which shows
"1 <name>", "2 <name>", …). Picking a position pops back. Unchecking opens nothing. The only way to move an
item that is already on is to uncheck it and check it again. Storage: `MainUIData#isMenuItemEnabled`
/ `setMenuItemEnabled` / `setMenuItemDisabled` and `getMenuItemsOrdered` / `setMenuItemIndex`.

**Phone default** = `MENU_ITEM_DEFAULT` (MainUIData.java) + Share (one-shot `CardMenuMigration`, only for
menus nobody customised, which also moves Share after Download and Block after Play next) + Watch later
(one-shot MMA migration).

**Where these menus appear on the phone.** (1) The card "…" button or a long press in Home and all You
sections: MBA:1407 -> `BP.onVideoItemLongClicked` BP:585 -> `VideoMenuPresenter`. A channel card in
You > Channels goes to `ChannelUploadsMenuPresenter` instead, which reads only Pin to You. (2) Search
results: `SearchPresenter`:115. (3) The channel page: `ChannelPresenter`:122. (4) Channel uploads and playlists:
`ChannelUploadsPresenter`:103. (5) A long press on a bottom tab or a You section row: MBA:1102/1675 ->
`BP.onSectionLongPressed` BP:709 -> `SectionMenuPresenter`. **None of this reaches the player**: its
related rows have no menu (`RelatedVideoAdapter` has a click and a press-to-prefetch only), and the
player's own "…" sheet never reads `MainUIData` menu items. Items marked *auth* are dropped when signed out
(`MenuAction.isAuth`).

| # | Settings label | Row label at runtime | Default (phone) | Phone reader (file:line) | Verdict | Where it can appear on the phone | Notes |
|---|---|---|---|---|---|---|---|
| 1 | Return to video running in background | same | off | none. `appendReturnToBackgroundVideoButton` VMP:849 / SMP:214 is gated by a hard-coded `true` (VMP:1182), not by this bit | DEAD | Card and section menus whenever `PlaybackPresenter.isRunningInBackground()`, **whatever the checkbox says** | The checkbox and its position are both ignored. |
| 2 | Play | Play | off | VMP:781 (flag VMP:1199) | LIVE | Any card with a videoId | Redundant on a phone, where tapping a card already plays it. |
| 3 | Play incognito | Play incognito | off | VMP:795. `incognito` is honoured by VSC:396 | LIVE | Any card with a videoId | Skips watch history for that play. |
| 4 | Play from start | Play from start | off | VMP:810 | LIVE | Any card with a videoId | Resets the saved position, then plays. |
| 5 | Remove from history | Remove from history | on | VMP:609 | LIVE | History section cards | |
| 6 | Set stream reminder | Set / Unset stream reminder | on | VMP:998 | LIVE | Upcoming streams only | |
| 7 | Add/Remove from recent playlist | "Add to <last playlist>" / "Remove from …" | off | VMP:283 | LIVE | Video cards, signed in, after "Save to playlist" has been used once | Needs item 9 on as well (VMP:298). |
| 8 | Save to Watch later | Save to Watch later | on (migration) | VMP:227 | LIVE (auth) | Video cards, signed in | |
| 9 | Save to playlist | Save to playlist | on | VMP:246 | LIVE | Video cards | |
| 10 | Create playlist | Create playlist | on | BMP:357 | LIVE | Only on non-video (playlist) cards while the Playlists section is in front, and on the Playlists section's long-press menu when signed in | |
| 11 | Rename playlist | Rename playlist | on | BMP:425 | LIVE | **Any** card while the Playlists section is in front | Server-side rename, own playlists only. |
| 12 | New playlist | New playlist | on | BMP:377 | LIVE | Video cards | |
| 13 | Download | Download | on | VMP:267 | LIVE | Finished ordinary videos, while the phone's download handler is installed | |
| 14 | Share *[stmobile]* | Share | on (migration) | VMP:703 -> ADU:100 | LIVE | Cards with a video or channel id | Not in `MENU_ITEM_DEFAULT`, so a fresh per-account profile (see 1h) starts without Share. |
| 15 | Not interested | Not interested | on | VMP:369 | LIVE (auth) | Home cards with a feedback token or endpoint | Phone flow: `sendFeedbackPhone`. |
| 16 | Don't recommend channel | Don't recommend channel | on | VMP:438 | LIVE (auth) | Home cards | |
| 17 | **Hide** (`remove_from_subscriptions`) | Hide | on | VMP:642 (+VMP:669 for Notifications, which the phone does not have) | LIVE (auth) | Subscriptions cards that carry a feedback token | A one-word label with no object. |
| 18 | Mark as watched | Mark as watched | off | VMP:688 | LIVE | Video cards | |
| 19 | Sort playlist | Sort playlist | on | VMP:980 -> ADU:~1240 (server-side `setPlaylistOrder`) | LIVE (auth) | Cards while the Playlists section is in front | Own playlists only. |
| 20 | Play next | Play next | on | VMP:930 | LIVE | Video cards | |
| 21 | Block the channel | Block / Unblock the channel | on | VMP:564 | LIVE | Any card with an author | The first block switches the Blocked Channels section on. |
| 22 | Add/Remove from playback queue | Add to / Remove from playback queue | off | VMP:862/897 | LIVE | Video cards | |
| 23 | Playback queue | Playback queue | off | VMP:963 | LIVE | Video cards | Opens the queue as a dialog. |
| 24 | Open channel | Open channel / Open playlist | on | VMP:323 | LIVE | Cards with a channel | |
| 25 | Open playlist | Open playlist | on | flag hard-coded `true` at VMP:1181, **bit unread**. Order still honoured (VMP:1235) | PARTIAL | Cards that carry a playlist | The checkbox does nothing; only its position counts. |
| 26 | Subscribe *[stmobile]* | Subscribe / Unsubscribe | on | VMP:831 | LIVE | Video and channel cards | |
| 27 | Exclude this channel from SponsorBlock | same (toggle) | off | BMP:556 | LIVE | Cards with a channel or video | |
| 28 | Pin to You *[stmobile]* | Pin channel to You / Pin playlist to You | on | BMP:64 (also gates "Unpin from You" on section menus, BMP:176/195) | LIVE | Video, channel and playlist cards; section menus | Turning it off also removes the only "Unpin from You" on section menus. |
| 29 | Add/Remove playlist from Playlists section | Add playlist to Playlists section / Remove playlist from Playlists section permanently | on | BMP:226 | LIVE | Playlist cards | |
| 30 | Video description | Video description | off | VMP:728 | LIVE | Cards with a videoId | Opens a long-text dialog; the phone renders TYPE_LONG_TEXT. |
| 31 | Open comments | Open comments | off | VMP:760 -> `CommentsController.openCommentsDialog` -> `appendCommentsCategory` | PARTIAL **BROKEN** | VOD cards | The phone's `DialogRowAdapter` (around line 173) draws TYPE_COMMENTS as the stub "Not available on mobile yet." |
| 32 | Share embed link | Share embed link | off | VMP:720 | LIVE | Cards with a videoId | |
| 33 | Share link (QR code) | same | off | VMP:712 -> ADU:162 (opens a web QR page in the browser) | LIVE | Cards with a videoId | Pointless on a phone. |
| 34 | Select account | Select account | off | BMP:214 -> `AccountSelectionPresenter.show(true)` | LIVE | Every card menu and section menu | Opens the TV-style account picker, not AccountsSheet. |
| 35 | Pause history | "Resume history" (default) / "Pause history" | off | BMP:497 | PARTIAL (auth) | **Every** card menu (a VideoMenu has no section, so the BMP:509 filter passes) and the History section menu | It never pauses. It toggles `GeneralData` history state between ENABLED and AUTO and calls `enableHistory(false)`, which is a no-op (MediaServiceManager:503). Because AUTO is the default, the row reads "Resume history". |
| 36 | Clear history | Clear history | on | BMP:524 | LIVE | History section cards (auth) and the History section menu | Asks for confirmation. |
| 37 | Move section up | Move section up | on | SMP:284 **is overwritten by SMP:285** | DEAD | — | Item 38 controls both rows. |
| 38 | Move section down | "Move section up" + "Move section down" | on | SMP:285 / SMP:167 | LIVE | Section long-press menus | Reorders only the You list; the bottom bar order is fixed. |
| 39 | Rename this section | Rename this section | on | SMP:189 | LIVE | Long-press menus of pinned channel/playlist sections | |
| 40 | Check for updates | Check for updates | off | BMP:546 -> `AppUpdatePresenter.start(true)` -> phone update screen | LIVE | Section menus, signed in only | Duplicates About > Check for updates. |
| 41 | Add/Remove from subscription group | same | off | `ChannelGroupMenuProvider` via VMP:1257 | LIVE | Cards with a channel or video | Of unclear use on the phone. |
| 42 | Remove the subscription group | same | off | `RemoveGroupMenuProvider` via SMP:300 | LIVE | Long-press menu of a channel-group section | |
| 43 | Rename the subscription group | same | off | `RenameGroupMenuProvider` via SMP:300 | LIVE | same | |

`SectionMenuPresenter` also always adds "Refresh section", "Mark all channels as watched" (Channels
section only) and the background-video row. None of these has a setting.

### 1d. "Hide content" (checkbox list), GSP:135

Storage: `MediaServiceData#isContentHidden` / `#setContentHidden` (bitmask). Default mask =
`SHORTS_SUBSCRIPTIONS | SHORTS_HISTORY | UPCOMING_HOME` (MediaServiceData.java:313), so only "Hide upcoming
from Home" starts checked. All Shorts rows and the Notifications row are hidden on the phone (GSP:139, GSP:168).
None of these rows has a side effect beyond the write; each applies on the next load.

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Notes |
|---|---|---|---|---|---|---|
| Hide Mixes | `CONTENT_MIXES` | off | only `YouTubeHelper.filterIfNeeded` (YouTubeHelper.java:114), which only legacy `YouTubeMediaGroup.create` calls (YouTubeMediaGroup.java:300) | DEAD (likely) | (would hide Mix cards) | The phone's feeds are all v2 (BrowseService2, SearchService2, WatchNext v2 `MediaItemMetadataImpl`). Their filter, BMG:11-17, has no mix clause. `YouTubeMediaGroup` is created only on v1 continuations and in `YouTubeMediaItemMetadata`, which nothing constructs. |
| Remove watched videos from Watch later playlist | `CONTENT_WATCHED_WATCH_LATER` | off | VSC.syncWithPlaylists VSC:702 (after >95 % watched: `removeFromWatchLaterPlaylist`, **a server-side account write**); MGO:33 -> BMG:16 (hides >80 %-watched items when listing WL) | LIVE | Removes a Watch later video from that playlist once you finish it. | It edits the account, not just the view. |
| Hide watched videos from Home | `CONTENT_WATCHED_HOME` | off | MGO:32 -> BMG:16 | LIVE | Leaves out Home videos you have watched (over 80 %). | Uses YouTube's own watch progress, so it only works signed in. |
| Hide watched videos from Subscriptions | `CONTENT_WATCHED_SUBSCRIPTIONS` | off | MGO:31 -> BMG:16 | LIVE | Same, for Subscriptions. | Same caveat. |
| Hide streams from Subscriptions | `CONTENT_STREAMS_SUBSCRIPTIONS` | off | MGO:26 -> BMG:14 | LIVE | Leaves live streams out of Subscriptions. | |
| Hide upcoming from Subscriptions | `CONTENT_UPCOMING_SUBSCRIPTIONS` | off | MGO:27 -> BMG:15 | LIVE | Leaves scheduled premieres and streams out of Subscriptions. | |
| Hide upcoming from Home | `CONTENT_UPCOMING_HOME` | **on** | MGO:30 -> BMG:15 | LIVE | Same, for Home. | |
| Hide upcoming from Channel | `CONTENT_UPCOMING_CHANNEL` | off | MGO:28-29 (TYPE_CHANNEL / CHANNEL_UPLOADS) | LIVE | Same, on channel pages. | |

### 1e. "Background playback" (radio), GSP:361 -> ADU.createPhoneBackgroundPlaybackCategory ADU:278

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Picture in picture (only on devices with PiP) / Only audio | `PlayerData#getBackgroundMode` / `#setBackgroundMode`, plus `GeneralData#setBackgroundPlaybackShortcut(HOME)` on every pick | `BACKGROUND_MODE_DEFAULT`, which the phone treats as PiP (`PhoneBackgroundMode.isOnlyAudio`) | MPA:2526 `getBackgroundMode` -> `BackgroundModePolicy.onLeave` MPA:1719 / `autoEnterPip` MPA:2519 | LIVE | What happens when you leave a playing video: keep it in a small window, or keep only the sound. | **Misplaced**: this is player behaviour, and the same list is in the player's own menu (MPA:3929). The shortcut write has no phone reader. |

### 1f. "Network settings" (checkbox list), GSP:682

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Use Web Proxy (only listed when `ProxyManager.isProxySupported()`, i.e. API ≥19, so always) | `GeneralData#isProxyEnabled` / `#setProxyEnabled` (+ the proxy URI in AppPrefs, edited in `WebProxyDialog`) | off | `SP.initProxy` SP:212 -> `ProxyManager.configureSystemProxy` (sets the JVM `http(s).proxy*`/`socksProxy*` properties and replays `PROXY_CHANGE_ACTION` into Chromium's `ProxyChangeListener` by reflection on `LoadedApk.mReceivers`) | PARTIAL | Routes VoidTube's traffic through an HTTP/SOCKS proxy. | InnerTube/OkHttp follows the JVM properties. Whether the Cronet media path follows depends on that reflection hack on modern Android (unverified). Checking it **closes the whole Settings tree** and opens the proxy dialog (cancel there leaves the proxy enabled, per a FIXME in WebProxyDialog). It also writes `PlayerTweaksData.setPlayerDataSource(OKHTTP/CRONET)`, which the media3 stack never reads (ErrorFixerController:385 comment), and calls `OkHttpManager.unhold()`. |
| Enable Conscrypt (desc: "Improves HTTPS, TLS, and VPN compatibility on some devices, but may occasionally cause playback issues or slower network performance") | `NetworkData#isConscryptEnabled` / `#setConscryptEnabled` | off | `MainApplication.onCreate` MainApplication.java:71 (reached through `MobileMainApplication.super.onCreate`) puts Conscrypt first among security providers | LIVE | Uses the bundled TLS library for API calls instead of the system one. | Takes effect after a restart; closing General shows the "Please restart the app" toast. Affects JVM/OkHttp TLS only, not Cronet (which uses its own BoringSSL). |

### 1g. "History" (radio), GSP:580

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Auto (use account settings) / Enable history / Disable history | `GeneralData#getHistoryState` / `#setHistoryState` | `HISTORY_AUTO` | `SP.enableHistoryIfNeeded` SP:221 (ENABLED -> turns the account's watch history back on at every launch), `AccountSelectionPresenter`:140 (same on account switch), `VSC.updateHistory` VSC:562 (DISABLED -> VoidTube stops sending watch-time pings) | LIVE | Whether VoidTube records what you watch in your YouTube history. | Selecting a row also calls `MediaServiceManager.enableHistory()`, which acts only for `true`. "Disable" does not pause YouTube history; it only stops VoidTube reporting. "Enable" really means "force YouTube history back on". The same idea appears again as the "Pause history" menu item (1c #35) and as search history (§3). |

### 1h. "Misc" (checkbox list, header `player_other` = "Misc"), GSP:596

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Disable screensaver | `GeneralData#isScreensaverDisabled` | **on** | only `ScreensaverManager`:239, and the phone never creates one (`MobileActivity.createScreensaverManager` returns null, MobileActivity.java:252) | DEAD | — | Checked by default and does nothing. |
| Use separate settings per each account | `AppPrefs#isMultiProfilesEnabled` / `#enableMultiProfiles` | off | `AppPrefs.getProfileData` / `setProfileData` (AppPrefs.java:118/122): every `*Data` blob is keyed by `<account>_` | LIVE | Each signed-in account keeps its own settings. | `enableMultiProfiles` fires `onProfileChanged`, and every prefs class reloads from the per-account key. That key **starts empty (nothing is copied)**, so all settings snap back to upstream defaults, and the phone's one-shot migrations (Share, Watch later, caption style…) do not reach the new blob. Also calls `BP.updateSections()`. Shown in **three** places: here, Settings > Accounts, and AccountsSheet > Account settings. |
| Protect all settings with password | `GeneralData#getSettingsPassword` / `#setSettingsPassword` | null | **none**. Only GSP reads it; enforcement lived in the deleted TV `SettingsGridFragment:131` (tv-legacy) | DEAD | — | Misleading security. Checking it closes the Settings tree and asks for a password (`SimpleEditDialog`). Unchecking clears it without asking for the old one. |
| Enable master password | `GeneralData#getMasterPassword` / `#setMasterPassword` | null | `SP.checkMasterPassword` SP:377-395 (launcher cold start through SplashActivity, only when no screen is up; prompt "Enter master password", cancel exits), `SP.prefetchLinkedVideo` SP:112 | PARTIAL | Asks for a password when VoidTube starts. | Guards only cold starts that go through SplashActivity. A warm return, or an activity the system restores, skips it. **Unchecking clears it without asking for the password** (GSP:625), so anyone who reaches Settings can turn it off. Checking it closes the Settings tree. |
| Child mode (desc: "In this mode, the user can't use search or see any suggested content. The settings page will be password protected.") | `GeneralData#isChildModeEnabled` / `#setChildModeEnabled` | off | `SuggestionsController`:931 (the related list keeps only the playlist row), `VideoLoaderController.onNextClicked`:237 (Next ends playback) | PARTIAL | Hides suggestions in the player. | **The description is false on the phone**: search stays open (no reader), and Settings is not locked (see the settings password above). Turning it on asks for confirmation ("The app settings will be changed…"), then a settings password, then `enableChildMode` (GSP:723) rewrites the card menu to queue / play next / account / reminder / save playlist, turns off Home, Trending, Gaming, Music, News and Shorts, turns on History, Playlists, Subscriptions and Channels, sets playback mode LIST and disables popular searches. Top-bar and player-button bits are also written, but the phone reads neither. Turning it **off** resets the whole card menu to `MENU_ITEM_DEFAULT` (losing Share and any customisation), switches Trending back on, sets playback mode ALL, clears the settings password and closes the dialog. |
| Disable OK button long press (desc: "Intended for buggy controllers where OK button does not function properly") | `GeneralData#isOkButtonLongPressDisabled` | off | none (TV `LongClickPresenter` / `VideoPlayerGlue` are deleted) | DEAD | — | A remote-control concept. |
| Return to the launcher from ATV channels/search | `GeneralData#isReturnToLauncherEnabled` | off | `SP.enablePlayerOnlyModeIfNeeded` SP:409, but only when `IntentExtractor.isATVIntent`, an extra that only the Android TV launcher sets | DEAD | — | |
| Remember last viewed position in Subscriptions | `GeneralData#isRememberSubscriptionsPositionEnabled` | off | `BP.saveSelectedItems` BP:289 / `restoreSelectedItems` BP:300 -> `MBA.selectSectionItem(Video)` MBA:2519 (`scrollToPosition` when that video is in the current list) | UNCLEAR | (Tries to) bring you back to the last video you opened in Subscriptions. | On the phone the "position" is the last *tapped* card (`onVideoItemSelected` is called only from a tap, MBA:1367). The restore runs right after a section switch against whatever FeedCache snapshot is painted, with no pending-selection logic, so after a fresh load it is probably a no-op. Needs a device check. |
| Remember last viewed position in the pinned playlists | `GeneralData#isRememberPinnedPositionEnabled` | off | same, `isPinnedSection()` | UNCLEAR | Same, for pinned playlists and channels. | Same caveat. |
| Open corresponding section when the app launched from ATV Channels | `GeneralData#isSelectChannelSectionEnabled` | **on** | SP intent chain SP:315, which fires only for `youtube.com/tv#/zylon-surface?c=FE…` links emitted by Android TV launcher channels | DEAD | — | Reachable in theory, because the manifest accepts youtube.com VIEW intents, but nothing produces such links on a phone. |

**General screen side effects.** Closing it shows "Please restart the app to apply these settings"
only after Conscrypt has been toggled (GSP:53, `mRestartApp`). The password rows (settings password,
master password, child mode) call `settingsPresenter.closeDialog()`, which drops the user out of
Settings to Home before the AlertDialog appears.

---

## 2. User interface (`MUSP.show()`, MUSP:58; screen title "User interface")

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| **Theme** *[stmobile]*: System default / Light / Dark (radio; injected by MMA via `setPhoneTopRows`) | `ThemeMode#get` / `ThemeMode#set` (phone-only SharedPreferences) | System default on a new install, Dark on an upgrade from the dark-only app (`ThemeMode.init`) | `ThemeMode.set` -> `applyProcessWide` + `MobileActivity.checkTheme` on live screens; `MA.applyUiScale` adds the night override on new screens | LIVE | Light, dark, or follow the phone. | Applies at once. |
| **Where to grab card thumbnails**: Default / Start of the video / Middle of the video / End of the video (radio) | `MainUIData#getThumbQuality` / `#setThumbQuality` | `ClickbaitRemover.THUMB_QUALITY_DEFAULT` | `VideoCardAdapter`:348, `RelatedVideoAdapter`:237, MPA:4566 (the still image when a video opens) | LIVE | Show the uploader's thumbnail, or a frame taken from the start, middle or end of the video. | Interacts with DeArrow's thumbnail replacement (separate screen). |
| **Channels section sorting**: Last viewed / Alphabetically / New content (radio) | `MainUIData#getChannelCategorySorting` / `#setChannelCategorySorting` | `CHANNEL_SORTING_LAST_VIEWED` | `BP.updateChannelSorting` BP:483 (also called at init, BP:214/1744) -> the observable behind the Channels section (You > Channels) | LIVE | Order of the channel list in You > Channels. | Clears that section's fetch time, so it reloads on the next open. |
| **UI scale**: 0.4x … 1.4x, 19 choices (radio) | `MainUIData#getUIScale` / `#setUIScale` | 1.0 | `MA.attachBaseContext` -> `applyUiScale` MA:213 (densityDpi override); also `BasePlayerController`:53 and `MediaServiceManager`:493 | LIVE | Makes everything in the app bigger or smaller. | Shows the restart toast. New screens pick it up immediately; screens already open do not. |
| Misc: **Unlocalized video titles** | `MainUIData#isUnlocalizedTitlesEnabled` / `#setUnlocalizedTitlesEnabled` | off | `UnlocalizedTitleProcessor`:46 (in `BrowseProcessorManager`, used by the Browse/Search/Channel/ChannelUploads presenters and SuggestionsController) | LIVE | Shows video titles in their original language instead of YouTube's auto-translation. | **Silently turns off DeArrow "replace titles"** (`DeArrowData.setReplaceTitlesEnabled(false)`, MUSP:190). |
| Misc: **24 hours Time format** (label is two strings concatenated) | `GeneralData#is24HourLocaleEnabled` -> `GlobalPreferences` | the locale's convention | `DateHelper.toShortDate` DateHelper.java:102 (start time on upcoming-stream cards, CommonHelper.kt:117; channel-group items; RSS) | LIVE | 24-hour clock for times shown on cards (e.g. premiere start times). | Shows the restart toast. Narrow effect. |
| Misc: **Home: top right corner clock** | `GeneralData#isGlobalClockEnabled` | **on** | none (TV `NavigateTitleView`) | DEAD | — | Shows the restart toast for nothing. |
| Misc: **Player: top right corner clock** | `PlayerData#isGlobalClockEnabled` | off | none (TV `PlaybackFragment`) | DEAD | — | Also misplaced (a player row). |
| Misc: **Player: top right corner ending time** | `PlayerData#isGlobalEndingTimeEnabled` | off | none | DEAD | — | Same. |
| Misc: **Old look of Channels section** | `MainUIData#isUploadsOldLookEnabled` / `#setUploadsOldLookEnabled` | off | `BP.initSectionMapping` BP:317 (TYPE_GRID vs TYPE_MULTI_GRID) | PARTIAL | — | The phone draws both types as the same single grid (MBA never looks at the section type). The only difference is what a tap on a channel in You > Channels does (the multi-grid paths at BP:556/573). Shows the restart toast. |
| Misc: **Fullscreen mode (without system bars)** | `GeneralData#isFullscreenModeEnabled` / `#setFullscreenModeEnabled` | **on** | `MA.onCreate` MA:71/76; `MA.applyFullscreenModeIfNeeded` MA:319, which is overridden on the phone (MobileActivity:363, MPA:2032) so the bars are never hidden | DEAD for its purpose; **possibly harmful when unchecked** | — | Unchecking makes MA:78 apply `R.style.FitSystemWindows` (translucent status/nav bars + theme-wide `fitsSystemWindows=true`, common/res/values/styles.xml:4) on top of the phone theme, which probably adds stray insets everywhere (not verified on a device). Shows the restart toast. |
| Misc: **Show pinned channel as rows** | `MainUIData#isPinnedChannelRowsEnabled` / `#setPinnedChannelRowsEnabled` | **on** | `BP.enableRows` BP:1777 (pinned channel = its shelves as rows, or its uploads as a grid) | PARTIAL | — | The phone flattens rows into one feed, so this only changes the content (all of the channel's shelves vs. just its uploads), not the layout. Shows the restart toast. |
| Misc: **Show Playlists section as rows** | `MainUIData#getPlaylistsStyle` / `#setPlaylistsStyle` | GRID | `BP.updatePlaylistsStyle` BP:505 | PARTIAL | — | On the phone, "rows" means the videos of every playlist strung into one feed instead of playlist cards. Applies at once. |
| Misc: **Show Filter channels field inside Channels section** | `MainUIData#isChannelsFilterEnabled` | on | none (TV `MultiVideoGridFragment`) | DEAD | — | |
| Misc: **Search bar inside the Channel page** | `MainUIData#isChannelSearchBarEnabled` | on | none (TV `ChannelFragment`) | DEAD | — | |
| Misc: **Auto load Channels section content** | `MainUIData#isUploadsAutoLoadEnabled` / `#setUploadsAutoLoadEnabled` | **on** | `BP.onVideoItemSelected` BP:557, `BP.onVideoItemClicked` BP:575 (only while Channels is multi-grid, i.e. Old look off) | PARTIAL | — | ON: tapping a channel opens its uploads screen, but `onVideoItemSelected` (MBA:1367) first loads that channel's uploads *into the Channels grid*, because the phone ignores the second column. That is a suspected bug (back on Channels you would see uploads instead of the channel list); verify on a device. OFF: the tap replaces the channel list with that channel's uploads in place. |

**UIScaleSettingsPresenter**: unreachable. Nothing references it anywhere in `common/src/main` or
`smarttubetv/src` (grep). Its extra "Video grid scale" (`MainUIData#getVideoGridScale`) is read only by
`MediaServiceManager`:493.

---

## 3. Search (`SearchSettingsPresenter.show()` -> `appendPhoneRows`; screen title "Search")

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Disable search history (switch) | `SearchData#isSearchHistoryDisabled` / `#setSearchHistoryDisabled` | off | `SearchPresenter.onViewInitialized`:71 (the suggestion provider returns nothing for an empty query, so no history list); `SearchPresenter.disposeActions`:314 and `SearchSettingsPresenter.show` onFinish:42 -> `MediaServiceManager.clearSearchHistory()` | LIVE | Stops showing your past searches. | **A destructive account write, repeated**: while it is on, the signed-in account's YouTube search history is cleared every time you leave Search and every time this screen closes. The label does not say so. |
| Instant voice search (switch) | `SearchData#isInstantVoiceSearchEnabled` / `#setInstantVoiceSearchEnabled` | off | `SearchPresenter.startSearchInt`:294 -> `MobileSearchActivity.startVoiceRecognition`:932 | LIVE | Opening Search starts voice input straight away. | |

---

## 4. Language/Country (`LanguageSettingsPresenter.show()`)

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| "Language (<current>)" radio: "Default - <system language>" + `R.array.supported_languages` | `LocaleUpdater#getPreferredLanguage` / `#setPreferredLanguage` (GlobalPreferences) | "" (system) | `MA.attachBaseContext` MA:192 (`LocaleContextWrapper`, app strings); `LocaleManager`:86 (InnerTube `hl`) | LIVE | App language, and the language YouTube answers in. | Shows the restart toast. The phone's own strings exist only in English and Spanish (stmobile `values`, `values-es`), so any other language gives a mixed-language UI. |
| "Country (<current>)" radio: "Default - <system country>" + `R.array.supported_countries` | `LocaleUpdater#getPreferredCountry` / `#setPreferredCountry` | "" | `LocaleManager` (InnerTube `gl`, the region of the content); `BP.initSectionMapping` BP:327 (News dropped for RU/BY) | LIVE | Which country's YouTube you get (trending, news, recommendations). | Shows the restart toast. |

---

## 5. Accounts

Two entry points share `AccountSettingsPresenter` (a singleton):
* **Settings > Accounts** -> `show()` (line 55) -> the full TV dialog `createAndShowDialog` (line 80), titled with
  the avatar and account name.
* **You tab account row** -> `AccountsSheet.show` (MBA:976). This is a native bottom sheet with the
  account rows, "Use without account", Sign in, Sign out (with a confirm dialog) and "Account settings"
  *[stmobile]*, which calls `showAdvanced()` (line 64): only the last three switches below. So Settings >
  Accounts still shows the TV dialog that AccountsSheet was built to replace (its own Javadoc calls it
  "very complex", citing user feedback).

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| "Select account" radio: "None" + "<name> (<email>)" per account | `AccountSelectionPresenter#selectAccount` | — | the action itself (the account-change listener chain) | LIVE | Switch account, or browse signed out. | Closes the dialog. "None" is always created with `checked=true` (line 108), so when signed in two radios are probably shown as selected. |
| Sign in (button) | `YTSignInPresenter.start()` -> `MobileSignInActivity` | — | — | LIVE | Add a YouTube account. | Same as AccountsSheet's Sign in. |
| "Sign out" list (one row per account) | `SignInService#removeAccount` | — | — | LIVE | Remove an account from this device. | Confirm dialog, then closes, toast "Done", `BP.refresh`. |
| Protect this account with password (switch) | `AccountsData#getAccountPassword` / `#setAccountPassword` | null | `SP.checkAccountPassword` SP:170 (cold start: lock + `PlaybackPresenter.forceFinish`), `BP.initPasswordSection` BP:1753 (replaces every section with one error section **titled "Notifications"**, whose button "Enter account password" -> `showCheckPasswordDialog`), `SearchPresenter.onViewInitialized`:66 (Search closes at once until the password is accepted) | PARTIAL / UNCLEAR | Locks browsing on this account behind a password. | The phone's look of the lock is untested. Settings stays reachable from You, and AccountsSheet can switch account or sign out without the password. Turning it on closes the dialog and asks for the password; turning it off requires the old one. |
| Use separate settings per each account (switch) | `AppPrefs#isMultiProfilesEnabled` | off | see 1h | LIVE | see 1h | Duplicate of General > Misc (same pref). |
| Show account selection on boot (switch) | `AccountsData#isSelectAccountOnBootEnabled` / `#selectAccountOnBoot` | off | `AccountSelectionPresenter.show`:55 <- `SP.showAccountSelectionIfNeeded` (every launch, only with ≥2 accounts) | LIVE | Asks which account to use each time VoidTube starts. | Uses the TV-style picker dialog. |

---

## 6. Backup/Restore (`BackupSettingsPresenter.show()`; screen title "Backup/Restore")

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Local backup > "Backup app data:\n<path>" (path on Android 11+ = `/storage/emulated/0/Documents/SmartTubeBackup/<zip>`). Desc "NOTE: The backup will be created in zip format" when the backup and restore paths differ | `BackupAndRestoreManager#checkPermAndBackup` | — | the action | LIVE (device behaviour unverified) | Saves all VoidTube settings to a zip file. | Confirm dialog. Re-enables the Settings section. Toasts "Done" before the work finishes. The raw path is in the label, and the folder is still called **SmartTubeBackup**. |
| Local backup > "Restore app data:\n<app media dir>". Desc "NOTE: The backup zip should be copied to this folder manually or in file manager, use 'Open with'" | `getBackupNames` -> picker -> `checkPermAndRestore` | — | the action | LIVE | Restores settings from a backup zip. | On Android 11+ the user must first copy the zip into `Android/media/<pkg>`. "Nothing found" if absent. |
| Local backup > "Auto backup" -> None / Once a day / Once a week / Once a month | `GeneralData#getLocalDriveBackupFreqDays` / `#setLocalDriveBackupFreqDays` | **1 (daily)** | `LocalDriveBackupWorker.schedule`:37 <- `SP.runBackgroundTasks` SP:159 | PARTIAL | Backs up settings automatically. | **Bug: "None" never cancels.** The setter writes -1 first, and `cancel()` (LocalDriveBackupWorker:60) only cancels while freq > 0, so the periodic worker keeps running; `doWork` does not re-check. Changing the interval does nothing to an existing worker (`ExistingPeriodicWorkPolicy.KEEP`). Picking a frequency also runs a backup immediately (`forceSchedule`). |
| Google Drive > Backup app data / Restore app data | `GDriveBackupManager#backup` / `#restore` | — | `backupInt` -> if not signed in to Google -> `GoogleSignInPresenter.start(onDone)` | PARTIAL **BROKEN** | (would back up to Google Drive) | `GoogleSignInPresenter.start` -> `SignInPresenter.start` -> `startView(SignInView)` -> `MobileSignInActivity`, which always uses `YTSignInPresenter` (MobileSignInActivity:112). The user gets the **YouTube** sign-in screen, so Drive can never be linked. |
| Google Drive > Auto backup -> None/day/week/month | `GeneralData#getGDriveBackupFreqDays` | -1 | `GDriveBackupWorker.schedule`:51 (silently skips when not signed in to Google) | PARTIAL **BROKEN** | — | Same sign-in dead end, and the same "None doesn't cancel" bug (GDriveBackupWorker:93). |
| Google Drive > Backup for this device only (switch) | `GeneralData#isDeviceSpecificBackupEnabled` | off | `GDriveBackupManager`:247 (file-name suffix only) | DEAD (in practice) | — | Its only consumer is the Drive path above. |
| Google Drive > Sign in (button) | `GoogleSignInPresenter.start()` | — | see above | PARTIAL **BROKEN** | — | Opens the YouTube sign-in screen; finishing it would add a YouTube account. |
| "Import (GrayJay/PocketTube/NewPipe)" | `ADU.createSubscriptionsBackupButton` -> `MaterialFilePicker` | — | the action | UNCLEAR | Import subscription groups from another app's export. | Gated on `READ/WRITE_EXTERNAL_STORAGE` (`PermissionHelpers`). With targetSdk 37 those requests are auto-denied on Android 13+, so the picker likely never opens there. Closes the dialog first. The file picker is rooted in the app's files dir. |

---

## 7. About (`AboutSimpleSettingsPresenter.showPhone()`; screen title "About VoidTube")

| Row | Getter/Setter | Default | Phone reader | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Check for updates (desc "VoidTube <version>"). On `-Pfdroid` builds this row is just the version line and does nothing | `AppUpdatePresenter.start(true)` -> `PhoneUpdates.showUpdateScreen` | — | MMA hooks (`setPhoneUpdates`) | LIVE | Looks for a newer VoidTube. | |
| Notify about updates (switch; hidden on F-Droid builds) | `AppUpdateChecker#isUpdateCheckEnabled` / `#setUpdateCheckEnabled` (minIntervalMs > 0) | on | `AppUpdates`:192 (automatic checks), `AppUpdates`:464 (the You badge) | LIVE | Tells you when an update is out. | |
| Source code (desc "github.com/aleixrodriala/newtube") | `Utils.openLinkExt` | — | — | LIVE | Opens the repository. | |
| License (desc "MIT") | `Utils.openLinkExt` | — | — | LIVE | Opens the licence. | |
| Send diagnostic log (desc "For bug reports. Includes the videos and channels you opened; passwords and sign-in tokens are removed") | `DiagnosticLog.share` | — | `DiagnosticLog.start` in MMA | LIVE | Shares recent app logs for a bug report. | |
| Star VoidTube on GitHub *[stmobile]* (desc "Free and open source. A star helps others find it") | `AboutShareRows` | — | — | LIVE | Opens the repo to star it. | |
| Share VoidTube *[stmobile]* (desc "Send the link to someone who'd like it") | `AboutShareRows.share` | — | — | LIVE | System share sheet with the site link. | VoidTube's own share targets are excluded. |

---

## 8. Password / child-mode enforcement on the phone (direct answer)

* **Protect all settings with password**: not enforced anywhere. `MBA.openSettings` has no check; the only
  reader was the TV `SettingsGridFragment` (tv-legacy:…/SettingsGridFragment.java:131), now deleted.
* **Master password**: enforced, at cold start only: `SP.runPerViewTasks` -> `checkMasterPassword` (SP:149,
  SP:377) shows "Enter master password" through SplashActivity (the launcher activity), and cancel exits.
  Skipped when a screen is already up (`getTopView() != null`). The checkbox can be cleared without the
  password.
* **Child mode**: not enforced as described. Search and Settings stay open. Its only live effects are
  fewer suggestions and Next ending playback, plus the one-time rewrite of menus and sections it performs.
* **Account password** (Accounts screen): enforced through Splash, BrowsePresenter and SearchPresenter, but
  presented as a "Notifications" section with an error card, and it can be sidestepped through Settings
  and AccountsSheet.

---

## Summary

### Verdict counts (109 settings units: a radio group counts as 1, each checkbox/switch/button as 1, each context-menu item as 1; "Set-up sections" counts as 1 unit)

| Page | LIVE | PARTIAL | DEAD | UNCLEAR | Total |
|---|---|---|---|---|---|
| General: boot, sections, background, network, history | 5 | 1 | 0 | 0 | 6 |
| General: Context menu items | 38 | 3 | 2 | 0 | 43 |
| General: Hide content | 7 | 0 | 1 | 0 | 8 |
| General: Misc | 1 | 2 | 5 | 2 | 10 |
| User interface | 6 | 4 | 6 | 0 | 16 |
| Search | 2 | 0 | 0 | 0 | 2 |
| Language/Country | 2 | 0 | 0 | 0 | 2 |
| Accounts | 5 | 1 | 0 | 0 | 6 |
| Backup/Restore | 2 | 5 | 1 | 1 | 9 |
| About | 7 | 0 | 0 | 0 | 7 |
| **Total** | **75** | **16** | **15** | **3** | **109** |

Without the 43 context-menu items: 37 LIVE, 13 PARTIAL, 13 DEAD, 3 UNCLEAR (66 units). KEYBOARD-ONLY: 0.
The phone already hides the key-remapping, app/player/search exit shortcuts and screen dimming.

### DEAD rows (15)
* General > Context menu: **Return to video running in background** (bit never read; that row shows
  whenever a video runs in the background), **Move section up** (overwritten by the Move section down bit, SMP:284-285).
* General > Hide content: **Hide Mixes** (only the legacy v1 group filter applies it).
* General > Misc: **Disable screensaver** (checked by default, no screensaver on the phone), **Protect all
  settings with password**, **Disable OK button long press**, **Return to the launcher from ATV
  channels/search**, **Open corresponding section when the app launched from ATV Channels** (on by default).
* User interface > Misc: **Home: top right corner clock** (on by default), **Player: top right corner clock**,
  **Player: top right corner ending time**, **Fullscreen mode (without system bars)** (on by default, and
  possibly harmful when unchecked), **Show Filter channels field inside Channels section**, **Search bar inside the Channel page**.
* Backup > Google Drive: **Backup for this device only** (dead because its only consumer, Drive, is broken).

### PARTIAL rows worth acting on (16)
* **BROKEN**: context-menu **Open comments** (shows "Not available on mobile yet."); the **whole
  Google Drive branch** (Backup, Restore, Auto backup, Sign in), because the sign-in opens the YouTube
  device-code screen.
* **Misleading**: **Pause history** menu item (never pauses; reads "Resume history" by default);
  **Child mode** (description promises a search block and a settings lock that the phone does not have);
  **Enable master password** (cold start only, can be unticked without the password); **Protect this
  account with password** (lock shown as a "Notifications" section, bypassable).
* **Semantics changed by the phone UI**: **Open playlist** (checkbox ignored, position honoured); **Use
  Web Proxy** (OkHttp yes; Cronet media only through a reflection hack; also closes Settings);
  **Old look of Channels section**, **Show pinned channel as rows**, **Show Playlists section as rows**,
  **Auto load Channels section content** (TV layouts that only change the content or tap plumbing on the
  phone; the default Auto load ON path is a suspected bug that overwrites the Channels grid); Local
  **Auto backup** ("None" never cancels the worker, and interval changes are ignored).

### Misplaced rows
* **Background playback** (General) is a player setting and duplicates the player menu's own list; move it to Player.
* **Player: top right corner clock / ending time** (User interface) are player rows (and dead).
* **Use separate settings per each account** appears in 3 places (General > Misc, Settings > Accounts,
  AccountsSheet > Account settings); it belongs in Accounts only.
* **History** radio (General), **Disable search history** (Search) and the **Pause history / Clear history** menu
  items describe one subject: a "History & privacy" group.
* **Child mode / settings password / master password** (General > Misc) belong in a "Parental controls /
  Security" group (or should be removed; see dead/misleading above).
* **Context menu** customisation (General) is card UI; put it under User interface or a "Card menu" screen.
* **Boot to section / Set-up sections** (General) form a "Tabs" group, together with the long-press section menu.
* **Channels section sorting / Old look / Auto load / Pinned channel rows / Playlists rows** (User interface)
  are per-section behaviours; they fit better in each section's long-press menu.
* **Unlocalized video titles** (User interface) silently turns off DeArrow title replacement, so it belongs
  next to DeArrow.
* **Network** (proxy, Conscrypt): an "Advanced" group.
* **Settings > Accounts** still opens the old TV dialog while the You tab uses AccountsSheet; route the
  Settings row to the sheet.
* Context-menu **Check for updates** duplicates About.

### Confusing labels
"Set-up sections"; "Context menu"; the bare **"Hide"** menu item; "Add/Remove playlist from Playlists section"
(the runtime row says "…permanently"); "Where to grab card thumbnails"; two different screens with a "Misc" header;
"24 hours Time format" (two strings glued together); "Auto (use account settings)" / "Enable history"
(which forces account history on); "Pause history"; "Use separate settings per each account" (grammar, and it
silently resets settings); "Disable screensaver"; "Protect all settings with password"; the Child mode
description; "Fullscreen mode (without system bars)"; "Show … as rows"; "Old look of Channels section";
"Home: top right corner clock"; "Enable Conscrypt" / "Use Web Proxy" (jargon); "Return to the launcher from ATV
channels/search", "Open corresponding section when the app launched from ATV Channels", "Disable OK button
long press" (TV jargon); "Share link (QR code)"; "Rename this section" / "Move section up/down" (a "section" is a
You entry); Backup rows with raw file paths and the "SmartTubeBackup" folder name; Google Drive "Sign in"
(which opens YouTube sign-in); "Select account" used both as a category title and as a menu item; the
account-password lock appearing as a section named "Notifications".

### DEAD verdicts I am less than sure about
* **Hide Mixes**: medium confidence. The only mix filter is `YouTubeHelper.filterIfNeeded` on legacy
  `YouTubeMediaGroup`, and every phone feed I traced is v2. A device check (tick it, look for a Mix card on Home)
  would settle it.
* **Fullscreen mode**: dead for hiding the bars (certain), but unticking it may break insets app-wide (MA:78);
  check on a device before deciding whether to hide or force it.
* **Open corresponding section … ATV Channels**: dead in practice, but a hand-crafted
  `youtube.com/tv#/zylon-surface?c=FEsubscriptions` link would still trigger it.
* **Backup for this device only**: dead only because the Drive sign-in is broken; it would come back if
  Drive were fixed.
* The two **Remember last viewed position** rows are listed UNCLEAR, not DEAD. The code runs on the phone,
  but it keys on the last tapped card and likely restores against an empty or stale list.
* Certain (verified reader-less): screensaver, settings password, OK long press, return-to-launcher, both
  player clock rows, Home clock, channels filter, channel search bar, the "Return to video running in background"
  bit, Move section up.

### Side findings outside the strict brief (all from the code; none verified on a device)
* Auto backup "None" never cancels the periodic worker (local and Drive): `cancel()` checks freq > 0 after the
  setter has already written -1.
* With the default settings (Channels multi-grid + auto load), tapping a channel in You > Channels probably
  replaces the Channels grid with that channel's uploads (`BP.onVideoItemSelected` BP:556-558 sends a column-1
  group, which the phone grid treats as a replacement).
* Enabling "Use separate settings per each account" starts the new profile from empty prefs (no copy), so the
  phone migrations (Share in the card menu, Watch later, caption default) are lost for that profile.
* "Disable search history" clears the account's YouTube search history server-side on every exit from Search.
* "Remove watched videos from Watch later playlist" edits the account's Watch later list.
* Settings > Accounts' radio always marks "None" as checked (AccountSettingsPresenter:108).
