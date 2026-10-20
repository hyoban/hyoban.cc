# 这里, Moments for Android

The app that shows the site's Moments, places and trips on the phone: the places on a
map, every Moment with its photos and videos, and each trip's route and days. It reads
the API's public endpoints (`apps/api`) and keeps a copy under the app's `api/`
directory in the layout `packages/data-spec` defines, so it opens straight away and
works offline; each start, and each pull down on a list, asks only for what changed
since, the Moments, places and trips in one feed, and asks for the routes again only
when the API's revision has moved on, getting them only when they changed.

A new Moment can be written on the phone, from the ✎ in the Moments tab's header or
`moments://new`: its words, up to nine photos from the system's documents picker, and when and
where, worked out as it is written. The time is the earliest photo's, from its EXIF and
the offset the camera wrote, else now; the place is where the first photo was taken,
when the app may see that (the photo picker takes it out, the documents picker doesn't),
else where the phone is, and the known place within 2 km of
it, else a new place named after what is there (see below), or its coordinates.
Both can be changed: the time with the system's date and time pickers, the place on a
map with a pin at its middle, dragged to a new place or onto a known place's dot. A new
place takes the name of the nearest park, sight, shop or quarter OpenStreetMap knows
around the pin, through Photon (`BuildConfig.PLACE_SEARCH_URL`), else its street, and the
others around it are offered as names to take instead; where Photon can't be reached, the
system's geocoder names it. A place found in Amap comes in by sharing it: its share
sheet's 更多 offers 这里 (作为时刻的地点), which opens a new Moment with the place, or
gives it to the one being written; 保存为地点 beside it only keeps the place, as one of
the phone's own, and leaves Amap in front (one of the same name within 300 m isn't kept
twice). Amap's short link is followed to the point it
redirects to, in GCJ-02, which is shifted back to WGS 84.

Photos come in by sharing too: the gallery's share sheet (under 更多 on ColorOS, which can
pin it) offers 这里 (写一个时刻) for one or several, which opens a new Moment with them,
or adds them to the one being written. They are copied in at once, as they are, while the
sharing still lets them be read; ColorOS's gallery leaves where a photo was taken in
unless its share sheet is told to take it out (隐私和格式选项), so the Moment's place
comes from them as from photos picked in the app.
Such a Moment stays on the phone, under the app's `local/`
directory in the spec's layout, its photos copied in whole so it keeps them whatever
becomes of the gallery's, and its new places with ids of their own (`p-<ulid>`); no sync
or change of API touches it. It is shown among the site's, marked 仅本机 (on this phone),
and can be changed or deleted from its page.

With the API's token, set on the settings page (kept in a file of the app's that backups
leave out), the site's Moments can be changed and deleted from their pages too. Nothing
reaches the site as it is saved: the phone's new Moments, the site's changed (marked
改动未发布) and those deleted wait on the phone, past a restart, until they are
published together. The ☁ in the Moments tab's header, with how many there are,
opens 发布更改, which lists them, the changed with what changed, and takes back a change
or a deletion; its 全部发布到网站 uploads the originals of the new photos, one by one,
then sends all the rest as one `POST /v1/changes`, which the API takes whole or not at
all: the places only the phone knows, named by the API from their pinyin, the Moments,
each published, and the deletions. What is published leaves the phone's store for the
site's.

A Moment's share button offers 发布到 X, 分享到 Telegram, 分享到小红书, 分享到飞书,
发布到 Threads, 分享到抖音, 微信朋友圈（手动分享）, B 站（手动分享） and 准备图文.
The X option opens a separate editable copy of its words and a choice of up to four
photos, kept in their original order. Up to four are selected
initially; with more, none are selected until they are chosen. Live photos use their
stills; videos are not included. 打开 X 编辑器 prepares the files and hands them to the
installed X app's post composer, where the text limit, account, audience and final
publication are checked. It never posts automatically or marks a Moment as posted
to X. This uses Android sharing, without an accessibility service. If X's composer
is unavailable, the preview stays open with an error, rather than opening its
separate direct-message share target. Each share has its own cache directory;
directories older than a week are cleaned up on the next share.

分享到 Telegram uses the installed Telegram app's Android share receiver, with all
photos initially selected and kept in their original order. The recipient and final
send are chosen in Telegram. Its multiple-photo receiver ignores the accompanying
text, so the preview explains that 复制文案并打开 Telegram copies the edited text:
paste it into Telegram's caption field after choosing a recipient. A single photo
or text alone is handed over directly. This flow does not operate Telegram or send
messages automatically.

分享到小红书 uses its Android share receiver, with all photos initially selected in
their original order. On Xiaohongshu 9.50.0, both single and multiple photos open
the note editor but ignore accompanying text. 复制文案并打开小红书 copies the edited
text first; paste it into the body and add a title in Xiaohongshu before publishing.
Text alone opens its 写想法 editor with the text filled in. Photo and text limits are
checked in Xiaohongshu; this app does not publish automatically.

分享到飞书 uses its Android share receiver, with all photos initially selected in
their original order. On Feishu 8.2.6, text alone is shown in the share preview.
Single and multiple photos are previewed without the accompanying text, and the
recipient confirmation's 留言 field starts empty. 复制文案并打开飞书 copies the edited
text first; choose 发送给同事或群, select a recipient, paste into 留言 and confirm in
Feishu. This app does not choose a recipient or send messages automatically.

发布到 Threads uses its Android share receiver, with all photos initially selected
in their original order. On Threads 452.0.0.33.64, text alone opens a share menu:
choose 发布 to reach the composer with the text filled in. Single and multiple
photos open the composer directly but leave its text empty. 复制文案并打开 Threads
copies the edited text first; paste into the composer and review before publishing.
The destination checks its photo and text limits. This app does not operate the
share menu, choose a direct-message recipient or publish automatically.

分享到抖音 opens the installed app's `SystemShareNewActivity` receiver explicitly.
Douyin receives read-only access to each shared file so its internal transition to
the editor can still read the photos after the receiving activity closes. These
grants are revoked when the corresponding week-old share cache is cleaned up.
It requires at least one photo. With no photos selected, the preview explains that
text-only sharing is unsupported and offers 准备图文 with the edited text preserved.
On Douyin 40.4.0, three photos were verified through the post form. The system
share page offers 去发布 alongside chat recipients.
Choose 去发布, edit the images and tap 下一步 to reach the post form. The description
is not filled from the share text, so 复制文案并打开抖音 copies it for pasting there.
The user reviews the destination's photo, music and publishing options and confirms
the final post in Douyin. This app does not choose recipients or publish.

微信朋友圈（手动分享） and B 站（手动分享） both open 准备图文, with a reminder to
select the saved photos and paste the copied text in the destination app.
准备图文 is the manual fallback for WeChat Moments, Bilibili and other apps: edit a copy of the
text and select photos, save them to a separate `Pictures/这里分享-…` album, then copy
the text. It also works with just text or just photos. There is no platform photo
limit in this flow; all photos start selected. Files are numbered in their original
order, with insertion order and timestamps arranged for newest-first galleries.
The result shows the album name and count; the album stays on the phone for the user
to select in the destination app and remove when no longer needed. Only Android 9
asks for storage permission; newer Android versions use MediaStore without it.
A failed partial save removes the rows it created. Nothing operates another app or
publishes automatically, and no accessibility service is registered. If sharing
to X, Telegram, Xiaohongshu, Feishu, Threads or Douyin fails, 准备图文 is offered with the edited text and
selection preserved.

On the home screen it is 这里 (here): Material 3's nine-lobed cookie shape in green on
pale mint, with the map's "you are here" pulse in it, as an adaptive icon with its own
monochrome layer for themed icons.

Two home screen widgets read the same copy, so they draw offline: 那年今天, a Moment with
a photo from this day in an earlier year, or one picked at random when there is none,
the same all day; and 旅程, the trip under way, else the last one, else the next, with
one of its photos. Each comes in ColorOS's card sizes, a row, a square, a wide card with
the photo beside the words and a large one with the Moment's words whole, rounds as the
launcher rounds its own, opens its page when tapped, takes the colours the theme page
sets, and is drawn again after each sync and every three hours.

A video in the viewer goes on in a picture-in-picture window when the app is left while
it plays, with the system's play and pause; with its sound on it takes the audio from
other apps and stops when headphones come out, and the screen stays on while it plays.
The photos and videos are SDR (WebP without gain maps, BT.709 video), so the viewer
leaves the window's colour mode alone.

The app speaks Chinese, and English on a phone set to it, or when it is chosen on the
settings page, which sets the system's own per-app language on Android 13 and later,
where ColorOS doesn't offer it in its settings, and keeps it in the app before: its labels, the
counts in each language's plurals, and the dates as each language writes them. The
Moments' own words stay as they were written.

## Building

Android Studio opens `apps/android` as a project. From a shell, with the Android SDK
in `local.properties` (`sdk.dir=…`) and a JDK 17 or newer:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The maps are OpenFreeMap's vector tiles drawn by MapLibre, which need no key.

The API, the origin the photos and videos come from and the map's light and dark styles
are the build's (`BuildConfig`) until changed on the settings page, opened from the
Moments tab's header; a new API drops the copy on the phone and reads everything again.
The same page sets the look as FlClash's theme page does: light, dark or the system's,
the seed colour (the system's accent, Apple's system colours or one's own by hex), how
much of it the scheme carries, and dark in pure black.

The unit tests round-trip every record under the repository's `data/` through the
app's models, so the app and the site
can't drift apart without a test noticing; they also check the media URLs against the
site's rules, which trip a Moment is on, and the sync against a stand-in for the API.

## On the phone, from adb

Every page has a link, `moments://moments`, `map`, `trips`, `settings`,
`new`, `moment/{uid}`, `trip/{id}`, `day/{YYYY-MM-DD}` and `place/{id}`, which
`adb shell am start -d` opens straight away, going over the page shown when the app is
already open. The buttons, tabs and cards carry test tags that `uiautomator dump`
reports as resource ids (`tab_map`, `settings`, `back`, `moment_{uid}`,
`media_{uid}_{index}`, `trip_{id}`, `day_{date}`, `year_{year}`, `locate`, …), and
`scripts/ui.py` uses both:

```sh
scripts/ui.py open moment/01M466J3KG95G3V62CZ1C7ZZBH
scripts/ui.py dump
scripts/ui.py tap tab_map
```

The same names serve TalkBack: a photo is read as what it is, where it is among its
Moment's and its description; a calendar day as its date and how many Moments it has;
the months and days heading a list as headings; the theme's choices as a group with the
one chosen.

## Layout

- `data/spec` — the records as Kotlin, each keeping the fields it doesn't know.
- `data/api` — the API's change feed, the Moments with the places and trips that changed, and its writes.
- `data/store` — the copy on the phone, and the repository that brings it up to date
  and gives the screens what the site shows (no hidden Moments).
- `data/local` — the Moments written on the phone: ULIDs, their photos' time and place,
  where the phone is and what the place there is called.
- `data/media` — photo, poster and video URLs, picked as the site picks them, or the
  phone's own copy of a photo not yet published.
- `data/trips` — which trip a Moment is on, a trip's days and its stops in order.
- `widget` — the home screen's widgets (Glance) and what they pick for the day.
- `ui` — Compose screens: the map, the Moments, a Moment's page, a new Moment, the trips, a trip, the settings.

The app's own `data/` directory from when it recorded Moments is left on the phone as
it was.

## Thanks

The way this app looks is learned from [FlClash](https://github.com/chen08209/FlClash).
Its pages showed how calm and clear an Android app can feel: rows gathered into soft
cards under small captions, a quiet note beneath each group, a theme page where every
choice is shown rather than named. This app grew up copying those habits, one page
after another, and is much better for it. Thank you to chen08209 and everyone who works
on FlClash for making something so carefully, and for making it open for others to learn
from. The settings page carries the same thanks, with a link back to it.
