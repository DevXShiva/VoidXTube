# VoidTube Changelog

All notable user-facing changes to VoidTube (formerly "MeTube"). VoidTube is a fork of NewTube; for NewTube's own history, see [NewTube's releases](https://github.com/aleixrodriala/newtube/releases). Earlier entries below are kept as they were written, under the project's former name.

## Unreleased — VoidTube rebrand

### Changed

- **The project is now VoidTube.** New name, tagline (“Your Media. Your Control.”) and app id (`io.github.voidxdevs`), so it installs as its own app. APKs are named `VoidTube_<version>_<abi>.apk`.
- **New look: premium dark, electric violet + cyan.** The accent moves from the fork's red to an electric-violet brand colour with cyan secondary accents, on black/charcoal surfaces. Launcher icons, the in-app mark, the splash and the loading indicator were recoloured to match.
- **Telegram + credits.** Settings › About now links the VoidTube Telegram channel and shows the developer credit; brand values are overridable at build time (see the README's Configuration table).
- Upstream attribution to NewTube and SmartTube is unchanged.

## 3.0.1 — 2026-10-19 — YouTube's search filters, Shorts in search, a real channel page

### New

- **YouTube's search filters.** The filter button opens the same "Search filters" YouTube has: Type, Duration, Upload date and Prioritize, plus Features chips: Live, 4K, HD, Subtitles/CC, Creative Commons, 360°, VR180, 3D, HDR, Location and Purchased. Any combination works now; before, many combinations quietly searched without a filter (#11).
- **Shorts in search.** Search results show Shorts the way YouTube does: a block of tall cards, two to a row. Tap one to open the Shorts player and swipe through the search's Shorts. Type › Shorts shows only Shorts (#11).
- **A channel page with a header.** The channel's banner, picture, name, @handle, subscribers and video count and its description (tap to read it all), over a soft blur of the banner. The header scrolls away; the tabs stay (#10).
- **Both channels on a collab.** A video made by two channels shows both channel pictures, like YouTube.
- **Hide the tab names.** Settings › Customization › Tab names: off leaves only the icons in the bottom bar.

### Changed

- Channel pictures on cards load sooner while you scroll and fade in instead of popping in.
- The search filters YouTube no longer has (Last hour, sort by upload date or rating) are gone.

## 3.0.0 — 2026-10-19 — Your own bottom bar, three app icons, a truthful quality label

### New

- **Choose your bottom bar.** Home, Search and You always stay; the tab before Search and the tab after it are yours: Shorts, Subscriptions, Downloads, History or none. Settings › Customization › Tabs. Picking what the other tab shows swaps the two. Subscriptions can be on the bar again (#7).
- **A third icon: MeTube (Fantasy).** The red heart with a white play button on a white tile, beside MeTube (default) and MeTube (Classic). The icon list shows each one.
- **The icon is everywhere.** The icon you pick now also shows in the Home pill, the launch screen and the update sheet, not only on the home screen. Picking one offers Restart to switch right away.

### Changed

- Settings choices (theme, app icon, interface size, ...) open in a bottom sheet instead of a pop-up in the middle of the screen.
- Switches and the picked option are red, the app's accent colour.
- Trending is gone from Tabs and You: YouTube removed it and its feed came back empty.

### Fixed

- **"Auto (1080p60)" on videos without 60 fps.** In Auto the Quality row showed the Auto ceiling instead of the quality playing. It now shows the real one, for example "Auto (720p)" in the small player and "Auto (1080p)" in fullscreen (#8).
- **Subscriptions never reached the bottom bar** when turned on in Settings › Tabs (#7).
- Long tab names such as Subscriptions fit their tab instead of ending in "...".

## 2.9.9 — 2026-10-18 — Shorts in picture-in-picture, channel pictures, a new icon

### New

- **Shorts in picture-in-picture.** Press Home while a short plays and it keeps playing in a portrait window with previous, play/pause and next, like YouTube.
- **Channel pictures on cards.** Home, Subscriptions and Search show each channel's picture beside the video title. Tap the picture or the channel name to open the channel.
- **Sort a channel.** The new button on a channel page sorts its videos by Latest, Popular or Oldest.
- **Choose your double-tap.** Settings › Playback › Double-tap to seek: 3, 5, 10, 15 or 30 seconds.
- **A new icon.** A glossy heart with a play button, on the home screen, the launch animation and the Home pill. Prefer the old one? Settings › Customization › App icon › Classic.
- **Customization page.** Settings › Customization holds the theme, app icon, glass, interface size, start screen, Home layout, tabs, video menu, thumbnails and playlist style. General keeps language, country, voice search and number formats.
- **Update pop-up.** When a new version is out, MeTube asks once with Update now or Later. Later asks again a day later; Don't ask again turns it off (Settings › About › Update pop-up).

### Changed

- Group titles in Settings and pop-ups are smaller and red, so they no longer look like options.
- The account and Buy me a coffee rows left the top of Settings. Switch accounts from the You tab; the thank-you stays at the bottom of About.
- Updating to 2.9.9 removes MeTube from your home screen once (the launcher entry was renamed for the icon choice). It is still in the app drawer; pin it again from there.

### Fixed

- **Ads in the Shorts feed.** Promoted shorts are now dropped.
- **Shorts wouldn't swipe right after opening.** The Shorts tab could open on a single subscription short before the rest of the feed arrived, and a swipe did nothing. It now waits for the next short.
- **Shorts picture-in-picture turned landscape on next.** The window keeps the short's shape while the next one loads.
- **Home could crash** reattaching the mini player's video.
- Searches that failed once are retried properly, and captions are no longer offered from auto-dubbed audio (from SmartTube).

## 2.9.8 — 2026-10-17 — Home filters, sleep timer, steadier background play

### New

- **Filters on Home.** Liquid-glass chips at the top of Home: All, Music, Gaming, News, Sports and Live. They slide away as you scroll down; scrolling back up shows a small arrow beside the MeTube pill that brings them back.
- **Home as rows.** Settings › General › Home layout › Rows shows Home like SmartTube: one titled row per topic that you swipe sideways. Feed stays the default.
- **Channels on Subscriptions.** A row of your subscribed channels heads Subscriptions. Tap one to see only that channel's videos; tap All for everything again.
- **Sleep timer.** In the player's settings: pause after 10 to 90 minutes, or at the end of the video.
- **Autoplay switch.** In the player's settings, one tap turns playing the next video on or off.
- **Video quality for Wi-Fi and mobile data.** Settings › Video quality has a default for each. Mobile data starts as "Same as Wi-Fi".

### Fixed

- **Music stopped, and the app was closed, with the screen off.** MeTube now keeps the phone awake and connected while it plays, and asks once to be allowed to run in the background, which battery savers on phones like realme respect. Settings › Playback › "Keep playing with the screen off" shows whether it's allowed.

## 2.9.6 — 2026-10-15 — Hotfix

### Fixed

- **The player could freeze half-shrunk.** A back swipe in the vertical fullscreen left the video stuck part-way to the mini player, with the page dimmed and no button or gesture working. Back now just leaves the vertical fullscreen, and if the player is ever caught half-way, Back puts it right.

## 2.9.5 — 2026-10-15 — Headphones on the mini player, iOS-style switches

### New

- **Headphones on the mini player.** The in-app mini player has a headphones button to switch to audio only, and so does its floating picture-in-picture window.
- **iOS-style switches.** Settings switches are compact capsules with a white pill knob that turns into clear glass as it slides.
- **Frosted search in Settings.** The "Search settings" bar is liquid glass, and the list scrolls under it. It follows the Liquid glass setting.
- **Buy me a coffee.** If you love MeTube and want to support it, there's a link in Settings and a small thank-you at the bottom of About.

### Changed

- **Pulling down into the vertical fullscreen follows your finger,** and springs back if you let go early. Going in and coming back out are smoother.
- **Smoother picture-in-picture from the mini player.** Its buttons hide before the window shrinks, and the card eases back into its corner when you return.
- **In the vertical fullscreen, the minimize and headphones buttons sit under the seek bar,** within reach of your thumb, and the top buttons stay clear of the camera notch.

## 2.9.3 — 2026-10-15 — Lyrics providers, smoother comments and fullscreen

### New

- **Choose your lyrics provider.** Like BitChord, the lyrics panel lists every source that has the song's words, and you can switch between them.
- **Glass buttons on Shorts.** Like, comments, share and the other buttons sit on the same frosted liquid glass as the player's buttons.
- **Remove from playlist and Watch later, from the card menu,** on your own playlists (from NewTube).
- **Channel links open,** including @handle links, and the channel page shows its name (from NewTube).

### Changed

- **Pulling Home to refresh shows videos you haven't seen.** The videos already on screen make room for the rest of YouTube's picks, with no extra wait.
- **Writing a comment shows a single bar,** with your picture and a rounded glass pill, and it rises smoothly with the keyboard.
- **The vertical fullscreen opens and closes smoothly,** without the page vanishing or the video jumping.
- **The Liquid glass setting covers every glass surface,** the player's and Shorts' buttons included.

### Fixed

- **The lyrics panel fades away with the player** when you swipe the video down to the mini player, like comments and the description.
- **No more stray Shorts tiles in a channel's navigation row,** and the untitled row of channel tabs is called "More" (from NewTube).
- **The screen can sleep when the mini player is paused under a menu,** and a dubbed video's download keeps the right audio if the app closes mid-download (from NewTube).

## 2.9.2 — 2026-10-14 — Lyrics, liquid glass player, Description panel

### New

- **Lyrics for every song.** The Music button is now Lyrics: a panel under the video with the song's words, found across BitChord's lyrics sources (lrc.red, LyricsPlus, KuGou, LRCLIB, YouTube Music, Genius and more). Timed lyrics light up line by line with the song; tapping the video still works the player.
- **Liquid glass player buttons.** Every button over the video sits on frosted glass that blurs the picture behind it, in portrait, landscape and the vertical fullscreen.
- **A YouTube-style Description panel.** Tapping the title opens the description in a frosted panel under the video, with likes, views and the date, and text you can copy. The video keeps playing, undimmed.
- **Swipe between a channel's tabs.** Slide left or right on a channel's videos to change tabs.
- **Frosted comments.** The comments panel, the "Add a comment" pill and the sort and comment menus are frosted glass.

### Changed

- **Swipe up to leave the vertical fullscreen,** as well as down, and it settles smoothly.

### Fixed

- **Comments by people with Arabic or Hebrew names read properly.** The name line no longer jumbles the date ("days ago 4 •"), and replies that are just a timestamp line up with the rest.

## 2.8.0 — 2026-10-13 — Search tab and filters, Shorts edge to edge, floating mini player

### New

- **Search has its own tab.** Search now sits in the bottom bar where Subscriptions was (Subscriptions is in the You panel), and the search icon left the top bar.
- **Search filters are back.** The button next to the search bar narrows results by upload date (last hour to this year), length, type (videos, channels, playlists, movies) and features (live, 4K, HDR), and sorts them by date, views or rating.
- **The mini player floats when you leave the app.** Going home while the mini player plays on Home now opens a picture-in-picture window. Expanding it brings you back; closing it stops the video.
- **Shorts fill the screen, edge to edge.** Ambient blur takes the strips under the status and navigation bars, and the video's edges fade into it.
- **A frosted glass search bar that glows as you type.** Results scroll under a frosted search bar with clear and voice search inside it. What you type glows in Siri's colours, and opening Search lights up the screen's edges.
- **A beating heart on launch.** The heart beats once on the start screen (Android 12 and later) and in the top bar.

### Changed

- **Shorts pause when you leave the app.** Shorts no longer open picture-in-picture, play in the background or shrink to the mini player; coming back plays them again.

### Fixed

- **Shorts no longer turn into normal videos at random.** A Short could lose track of the Shorts feed and open on the watch page, and the end of the loaded Shorts fell back to an ordinary next video.
- **Picture-in-picture fills its window for vertical and square videos.** It showed the video in the top half over black, with the fullscreen button below.
- **The screen sleeps when a video ends or is paused.** It used to stay on until the battery ran out.
- **An audio track you pick applies to that video only.** A dub chosen once no longer follows you to every dubbed video, and "en" no longer matches "ben".
- **Downloads of dubbed videos keep the original audio,** also after a retry.
- **Original titles work, with or without DeArrow.**
- **Backups leave your sign-in tokens out.** Android's backup and the app's backup file restore signed out.
- **Fullscreen works on tablets,** where Android 16 ignores rotation requests.
- **No blank Shorts tile on channel pages, and no channel actions on playlist cards.**

The last seven fixes come from NewTube 1.15.1.

## 2.5.7 — 2026-10-09 — Music button opens your music app

### Fixed

- **Music button opens whichever app you chose for YouTube Music links.** It used to open only the YouTube Music app. Now it follows Android's "Open by default" setting, so apps like BitChord work, and without a music app it opens in the browser.

## 2.5.6 — 2026-10-09 — Picture-in-picture every time, and a Music button

### New

- **Picture-in-picture whenever you leave a playing video.** Going home or switching apps now always shrinks the video into a floating window. The old "Play in background" setting is gone; the headphones button is how you ask for audio only.
- **Headphones in the picture-in-picture window.** Tap the window, then the headphones: the window closes and the sound keeps playing.
- **Music button.** For songs and music videos, a Music button next to Share opens the video in YouTube Music. It only shows for videos that YouTube Music has.

### Fixed

- **Smoother picture-in-picture.** MeTube no longer resends the window's buttons every time the video buffers, which stalled the window's animation.

## 2.5.1 — 2026-10-09 — Audio only, new icon and fixes

### New

- **Audio only.** Tap the headphones at the top left of the player to listen without the video: no video is downloaded, the thumbnail stays on screen, and pressing Home keeps the sound playing instead of opening a picture-in-picture window. Tap again for the video.
- **A new icon.** A red heart with a play button, on black, on the launcher and in the top bar.

### Fixed

- **No more crash after picture-in-picture at high quality.** At 1440p and above, MeTube kept up to two minutes of already-watched video in memory, and coming back from picture-in-picture could run out of memory. Video memory now has a hard limit tied to what the app may use; seeking back more than 20 seconds reloads from the disk cache.
- **Settings in fullscreen.** The settings and More menus no longer bring back the status and navigation bars, and no longer show a second plain panel beside the frosted one.
- **Diagnostic logs are named metube-diagnostics.**

## 2.5.0 — 2026-10-09 — Glass and quicker Shorts

### New

- **Up next countdown.** When a video ends, a small card in the player counts down ten seconds to the next one. Tap Play now to go at once, Replay to watch it again, or Cancel to stay put. Seeking back cancels it too.
- **A splash when you like or subscribe.** A ring and a burst of colour come off the button.
- **Floating glass top bar.** The full-width bar is gone: the MeTube logo and the cast and search buttons float as two liquid glass pills, like the navigation bar.
- **A quote when you tap the MeTube logo.** It shows for three seconds, and every tap brings a new one: no quote comes back until you have seen them all.
- **Frosted menus.** The player's settings, More and card menus are frosted glass over a blurred copy of what's behind them, while the page around them stays sharp.
- **Liquid glass setting.** Turn it off in Settings → General for a plain frosted blur that is easier on the battery.
- **Counts in K and M.** Views and likes read 250K and 12M even when your phone is set to English (India). Prefer lakh and crore? Turn on Lakh and crore counts in Settings → General.

### Changed

- **Shorts swipe faster.** MeTube now looks up the short after the next one in advance, and a short that was still preloading when you swiped to it plays from what it already has instead of starting over. Neighbouring thumbnails load at once.
- **Subscribed is a glass pill**, like the buttons above it.
- **The title and view count of a video are one button** that opens the description.
- **A small bounce on the navigation bar** when you switch tabs.
- **Downloads go to Movies/MeTube and Music/MeTube.** Earlier downloads stay where they are.

## 2.0.2 — 2026-10-08 — Vertical fullscreen

### New

- **Vertical fullscreen.** Pull the watch page down from its title and the video fills the upright screen, like YouTube's. The whole picture stays in view, the system bars hide, and the seek bar sits higher, within reach of your thumb. Swipe down on the video or press Back to leave it, or turn the phone sideways for landscape.
- **Ambient colours in vertical fullscreen.** The black bands above and below the video glow with its colours. Ambient mode is on by default; turn it off in Settings → Player → Watch page, or from the player's settings sheet.
- **A floating glass fullscreen button** at the bottom right of the watch page.
- **A new loading animation.** The coral cookie from the icon turns as it morphs into a four-leaf clover and back. It replaces the spinner app-wide.

### Changed

- **Shorts start faster.** While you watch a short, MeTube now preloads the first seconds of the next one: it asks for the next short 0.3 seconds in (it used to wait 5 seconds) and loads it once the current one has 3 seconds buffered. In testing, a short you swipe to after about 3 seconds started in about half a second.
- **A fatter seek bar while you drag it**, with a bigger dot, like YouTube's.
- **The minimize button in fullscreen** is a small round button, easy to spot over any picture.
- **No page titles in the top bar.** Just the MeTube logo, cast and search.
- **Shorts comments:** dragging them down no longer shows the short as an ordinary video underneath, and the short grows back to full screen as the comments slide away.

## 2.0.1 — 2026-10-07 — Feel

### New

- **Haptics everywhere.** Every button, card, chip, switch, menu row and bottom-sheet option answers your tap with a light click, and each double-tap seek forward or back clicks too. It follows your phone's touch-vibration setting.
- **True black for AMOLED.** In dark mode the pages, top bar and navigation area are pure black, so an AMOLED screen switches those pixels off and saves battery.
- **Shorts swipe like YouTube.** The next (or previous) short's picture slides in under your finger as you swipe, and stays on screen until the video starts, with no black flash. When nothing is queued yet, the swipe just springs back.
- **Scrub Shorts.** Drag the line at the bottom to jump around a short. A big time readout shows where you are, and the text gets out of the way while you drag.
- **More on Shorts.** A Shorts header with a back arrow; a "more" button for quality, captions and speed; and YouTube's spinning disc with the channel picture. Tap a title to read all of it.

### Changed

- **Shorts you've seen stay seen.** MeTube remembers the last 3,000 shorts for 7 days, across restarts, so the Shorts tab keeps serving new ones. If a feed has nothing new for three pages, it plays what it has instead of loading forever.
- **The Shorts progress line moves now.** In 2.0.0 it stayed empty while a short played.

## 2.0.0 — 2026-10-07 — Liquid Glass

### New

- **Shorts like YouTube.** The Shorts tab opens straight into the full-screen player, with no grid. The channel, a Subscribe button, the title and views sit at the bottom; like, dislike, comments and share on the right, with their counts; a thin progress line runs along the bottom. Tap to pause, double-tap to like, hold for 2x. Back returns to the tab you came from.
- **Endless, fresh Shorts.** The next page of Shorts loads before you reach the end, a short you have already seen this session is never served again, and the next one is buffered as soon as the current one starts.
- **Comments on Shorts.** The comments open under the short, which shrinks to the top of the screen, like YouTube.
- **Liquid glass.** The floating bar now bends what is under it like a lens, with a soft rainbow edge and a bright rim, and a lighter frost so you can see the page through it. The top bar is frosted glass too: the feed scrolls up under it.
- **A new icon.** A minimal coral "cookie" with a white M, in the Material 3 expressive style, with a themed version for Android 13+.

### Changed

- **Sharper Shorts thumbnails** (YouTube's tall 720×1280 images).
- **A bigger mini player** (216×122dp), landing exactly where the minimize animation ends.
- **Modern switches** in Settings: a rounded capsule, red when on.
- Like counts YouTube does not share no longer show "N/A".

## 1.5.0 — 2026-10-07 — Shorts

### New

- **Shorts.** A Shorts tab with a grid of tall cards. Tap one and it plays full screen; swipe up for the next short and down for the previous one. Each short loops until you swipe, and the next one loads while you watch. Like, dislike and share sit on the right.
- **Ambient glow.** On the watch page the colours of the playing video spread softly under the player and fade into the page, and follow the scene as it changes.
- **A new MeTube icon.** A rounded M on a coral-to-magenta tile, with a little glass bubble.

### Changed

- **Tap the title to read more.** The arrow is gone: tap a video's title or its views line to open the description, and the title shows in full.
- **Rounded everywhere.** Menus, sheets, dialogs, snackbars and chips have rounded corners, and the Comments panel slides up with a rounded top.
- **History moved to You.** The bottom bar is now Home, Shorts, Subscriptions, Downloads and You.

## 1.0.0 — 2026-10-07 — Floating Glass

### New

- **Floating glass.** The bottom navigation is now a frosted glass capsule floating over the page, with a live blur of what scrolls underneath. On Android 13 and later its edge bends the content like a lens.
- **A new look.** A darker page, a red accent, large tab titles, rounded thumbnails, and glass buttons and cards on the watch page.
- **MeTube.** A new name and app id, so it installs alongside NewTube. Updates come from github.com/yashoncode/metube.

### Changed

- **Dark by default.** Light theme is still in Settings → User interface → Theme.
