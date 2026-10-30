# 这里, Moments for Android

The app that shows the site's Moments, places and trips on the phone: the places on a
map, every Moment with its photos and videos, and each trip's route and days, which ▶
on a trip's page plays back as the site's player does (`packages/trip-map/src/player`),
with its cards, pacing and camera: the route never still while it plays, driving on from
each Moment toward the next place as its card shows, a car, ship, plane or train at its
tip with the day and time beside it, past each waypoint with a pause, the camera following
on springs, letting go while the map is panned or pinched until the next card; a card
over the map, stepped through from a floating toolbar or by a swipe, which goes through
a Moment's photos first, its photos a carousel whose next one peeks and is picked by a
tap, folded to its words by a swipe down or its handle to leave the map more room, opens
its Moment from its words or the photo showing, its menu when they are held or right
clicked; from a keyboard or a remote, Space and the media keys play and pause, left and
right step through the cards and up and down through the photos. The route is the stretches `GET /v1/routes` serves.

It has three tabs, floating at the foot of the screen as icons, 记录 beside them. 时间线 is one timeline of everything that happened, newest first: the
Moments under their months and days, the ledger's bills among them (those written with a
Moment at the foot of its card, the rest in groups between the Moments of their day, each
day's spending beside its date), and a banner where each trip begins. 地图 is the map, the
trips listed in the sheet at its foot. 我 holds the changes waiting to be published, the
ledger's month with the way into its bills, sums and accounts, and the settings, a page for
each of their sections. The look follows the app's design system (the 「这里 App 设计系统」
artifact, mirrored in `ui/theme`): the site's warm greys, one blue for state, hairlines
rather than shadows. It reads
the API's public endpoints (`apps/api`) and keeps a copy under the app's `api/`
directory in the layout `packages/data-spec` defines, so it opens straight away and
works offline; each start, each pull down on a list, and 刷新 on the settings page (the
same without the gesture) asks only for what changed since, the Moments, places and trips in one feed, and asks for the routes again only
when the API's revision has moved on, getting them only when they changed.

The pages lead to each other wherever they can: a place's page and a trip's show it on the
map tab, the place with its panel up and the trip's route lifted with its card, which the
trip's page shrinks into; a day's pill over its Moments opens the day, growing into its
page, and a day's page its trip; a Moment's menu, on its cards and in the playback, goes to
its place, its trip and its day; the line of when and where under the viewer opens the day; and 我 leads to the changes
waiting to be published. `moments://search` opens the search.

What a Moment says it ate shows as the site has it: a line under its words on its card
(the dishes, 好吃, 一般 or 踩雷, and the bill), and on its page a row after its place, with the
verdict, what the meal cost, how many it fed and each one's share under the dishes. A place's kind, 餐饮 or 景点, comes before its division on its page
and under its name in a Moment's place row.

The round button at the map's top right, 迷雾地图, lays a light fog over the map, as the site's
map does, thinner over a city the more Moments were written in it and gone over one with
about twenty: the prefecture-level cities of the site's
`src/data/boundaries/cities.json` (Aliyun DataV's, simplified; `vp run map:cities` makes it),
shipped in the APK as an asset, with the rest of the world never lit; the sea, lakes and rivers stay clear, so the fog meets the land at the basemap's own coast. A place on no city, on a
coast the boundaries were simplified past, lights the nearest within 10 km; the phone's own
Moments light theirs too. The fog is the page's text colour, so on the dark map it's a light haze. Turned on, it closes in from the screen's edges to its middle; turned off, it clears from the middle outwards. A tap while it sweeps turns it around from where it is. Left on, it sweeps in the same way the first time the map opens after the app starts. Under the toggle, how many cities and provinces are lit.

A new Moment can be written on the phone, from 记录 at the foot of the timeline or
`moments://new`, on a page of its own, or, on a wider window, in a dialog over the page it was opened from. Its bar holds
the X, whether it is 公开 or 私有 (a tap on it chooses) and the ink 保存; its words, photos, time and
place sit on the page itself, and under 加一项 a chip each opens 美食, 账单 and 类型, a bill
written with the Moment taking its time and place and linked to it, and a meal's amount and
food category until the bill's own are given. The
Moment: its words, up to nine photos and videos from the system's documents picker (a tile beside it
opens the phone's camera app, whose photos go to the gallery to be picked from there), and when and
where, worked out as it is written. A video goes up as it is, so one over 100 MB, more than
the API's Worker and the site's poster take, or other than MP4, MOV or WebM, is left out
as it is picked, with a dialog saying why. A photo tapped there can be marked 不用作封面, one kept
for the record (a screenshot, a flat tyre): it stays in its Moment, but neither the app's
trip covers and widget nor the site's covers, collages and share cards
pick it. Under 类型 it can say what it is mostly about, one of 技术, 旅行, 美食, 用车,
摄影 and 生活, a second tap leaving it unset; a 技术 one the site doesn't show. Under 美食 it can say what was eaten, as the site shows it under the Moment: the
dishes, split at commas, 好吃, 一般 or 踩雷, and what the meal cost and how many it fed, with each one's share;
the review before publishing shows a change to it in a line. The time is the earliest photo's, from its EXIF and
the offset the camera wrote, or a video's creation time in the phone's zone, else, for
photos whose EXIF has none (saved from WeChat), the
earliest their files were made, else now; the place is where the first photo or video was taken,
when the app may see that (the photo picker takes it out, the documents picker doesn't),
else where the phone is, and the known place within 2 km of
it, else a new place named after what is there (see below), or its coordinates.
Both can be changed: the time with the system's date and time pickers, the place on a
map with a pin at its middle, dragged to a new place or onto a known place's dot. A new
place takes the name of the nearest park, sight, shop or quarter OpenStreetMap knows
around the pin, through Photon (`BuildConfig.PLACE_SEARCH_URL`), else its street, and the
others around it are offered as names to take instead; where Photon can't be reached, the
system's geocoder names it. Its kind (城市, 景点, 餐饮 and the rest of the spec's) follows
what OpenStreetMap calls the place it is named after, and a row of chips under the name
changes it, for a known place too: the kind of one of the site's is then a change of its own,
listed under 改过的地点 until it is published, when the API takes it for that place; the API adds the province, city and district it is in when it is sent, and a
place's page shows them above its Moments' count. A place found in Amap comes in by sharing it: its share
sheet's 更多 offers 这里 (作为时刻的地点), which opens a new Moment with the place, or
gives it to the one being written; 保存为地点 beside it only keeps the place, as one of
the phone's own, and leaves Amap in front (one of the same name within 300 m isn't kept
twice). Amap's short link is followed to the point it
redirects to, in GCJ-02, which is shifted back to WGS 84.

Photos and videos come in by sharing too: the gallery's share sheet (under 更多 on ColorOS, which can
pin it) offers 这里 (写一个时刻) for one or several, which opens a new Moment with them,
or adds them to the one being written. They are copied in at once, as they are, while the
sharing still lets them be read; ColorOS's gallery leaves where a photo was taken in
unless its share sheet is told to take it out (隐私和格式选项), so the Moment's place
comes from them as from photos picked in the app.
Such a Moment stays on the phone, under the app's `local/`
directory in the spec's layout, its photos copied in whole so it keeps them whatever
becomes of the gallery's (a motion photo, its clip appended to the JPEG as the camera
writes it, is split there into a live photo's still and video, which its page plays from
the phone until it is published; picked, the app asks whether motion photos go in as
photos, their stills alone, which each one's options can change until it is saved; a video
is copied whole, with its first frame beside it as its still), and its new places with ids of their own (`p-<ulid>`); no sync
or change of API touches it. It is shown among the site's, marked 未发布 (unpublished),
and can be changed or deleted from its page. A Moment can be made 私有 (private) where it is
written: it is published for its owner's copy but never shown on the site, and the app shows
it, a private one or one about tech saying 私有 in its faint line.

With the API's token, set under 我 in 时刻与数据 (kept in a file of the app's that backups
leave out), the site's Moments can be changed and deleted from their pages too. Nothing
reaches the site as it is saved: the phone's new Moments, the site's changed (marked
改动未发布) and those deleted wait on the phone, past a restart, until they are
published together. The card at the top of 我, with how many there are (and a dot on 我's
tab while there are any), opens 发布更改, which lists them, the changed with what changed, and takes back a change
or a deletion; its 全部发布到网站 runs as WorkManager work in a foreground service, on
past the app being left or stopped, with how far it has got in a notification as on the
page, and over a link that drops it waits for the network and goes on (asked once, on
Android 13 and later, whether it may show that notification). It uploads the originals of
the new photos and videos, a live photo's still and video each, a few at a time,
then sends all the rest as one `POST /v1/changes`, which the API takes whole or not at
all: the places only the phone knows, named by the API from their pinyin, the Moments,
each published, and the deletions. What is published leaves the phone's store for the
site's.

A Moment's share button orders destinations by verified text/photo support:
发布到 X, 发布到 B 站, 分享到 Telegram, 发布到 Threads, 发布到小红书,
发布到抖音, 微信朋友圈, 飞书 (marked as posted by hand) and 准备图文, each app in an
outline after its own mark, drawn in the app's icon set rather than with its logo.
Complete text/photo composers come first, followed by assisted sharing, manual
caption entry, and destinations that require manually preparing photo albums.
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
or text alone is handed over directly. Without the optional helper, recipient
selection and caption entry remain manual. Nothing sends messages automatically.

The optional Telegram 分享助手（试用） adds a one-shot accessibility flow for photo
shares on Telegram 13.1.0. Enable 分享助手（试用） in Android's accessibility settings
and enter a public `@username` or `t.me/username` in the share preview. It searches
for the exact username, selects the matching result, verifies one recipient and
the photo count, then fills and reads back the caption. It stops before the final
send button; sending always remains a manual action. This currently recognizes
the English picker verified on the device and a limited set of Chinese labels;
other versions or layouts require the ordinary share flow with the assistant off.
No Shizuku is used. The service only reads Telegram while a requested share is
pending, cancels when leaving Telegram, and expires after 40 seconds. It does not
store chat contents or resume work after process death. Only the opt-in switch and
destination are saved locally. An unexpected existing caption is preserved; a
failure leaves the current Telegram screen for manual completion. Text-only
sharing continues to use the ordinary receiver.

`accessibility/ShareAccessibilityService` owns node snapshots, click/text actions,
gesture fallback, polling, and the lifecycle of one active `AssistSession`. An
`AssistAdapter` supplies its identity, target package, supported versions, timeout,
and result messages; each explicit share creates an `AssistRequest` with a fresh
`AssistFlow`. `share/TelegramShareAdapter` / `TelegramAssistFlow` and
`ThreadsShareAdapter` / `ThreadsAssistFlow` implement the two destinations.
The service has no app-specific page rules. Its event filter follows
only the current request's package and returns to this app when idle; the one
third-party reading while idle is the ledger's 自动记账, when it is turned on. Status,
cancellation, and reset are scoped to the adapter ID so another adapter cannot consume or cancel Telegram's task.

To add an app, implement its adapter and flow, validate the version at its share
entry, and call `ShareAccessibilityService.start(request)` immediately before
launching the prepared share intent. Add fixtures for its page recognition and
verify on device that it stops before sending. No additional accessibility service
or permission is needed. The manifest still registers the thin
`share/TelegramShareService` subclass solely to preserve the already granted
Android component identity; it contains no Telegram behavior. Keep that component
name stable when adding adapters. Existing Telegram preferences and manual
fallback are unchanged.

发布到小红书 uses its Android share receiver, with all photos initially selected in
their original order. On Xiaohongshu 9.50.0, both single and multiple photos open
the note editor but ignore accompanying text. 复制文案并打开小红书 copies the edited
text first; paste it into the body and add a title in Xiaohongshu before publishing.
Text alone opens its 写想法 editor with the text filled in. Photo and text limits are
checked in Xiaohongshu; this app does not publish automatically.

发布到 Threads opens `barcelona://create?text=…` for text-only posts, verified on
Threads 452.0.0.50.64. Text is URI-encoded. If the link cannot be opened, the original
Android share receiver is used: choose 发布 in its share menu. Photos still use that
receiver, with all photos initially selected in their original order. It does not
fill text alongside photos, so 复制文字并打开 Threads copies the edited text first.
Paste into the composer and review before publishing. An already open composer can
retain its previous text; finish that draft before starting another share.

The optional Threads 分享助手（试用） uses the same accessibility service and has
its own opt-in preference. On Threads 452.0.0.50.64 it recognizes the new-thread
composer by its view IDs and title, waits for photo attachments, fills its single
body field, and reads the exact text back twice. The horizontal photo row is
virtualized, and its collection item count includes other items, so the assistant
does not claim to verify the full photo count. It leaves attachments untouched
and asks the user to review both photos and text before publishing. It never clicks
Post, overwrites different existing text, or chooses between multiple editors. If a
share menu appears, choose the composer manually; a label such as 发布 is not
safe to click automatically because the final publish button has the same text.
Unknown pages or missing photo attachments stop after 40 seconds for manual
completion.
Text-only shares still use the original direct composer link. Turning the helper
off keeps the original photo-share and clipboard fallback. The service's settings
entry, status display, and cancellation control are shared with Telegram.

发布到抖音 opens `com.ss.android.ugc.aweme.share.SystemShareActivity`, verified on
Douyin 40.6.0 to go directly to the photo editor. Edit the images and tap 下一步 to
reach the post form. If this entry is missing or restricted, it falls back to the
previous `com.ss.android.ugc.aweme.share.ui.SystemShareNewActivity`: choose 去发布
there. Both use the same prepared files. The description is not filled from the
share text, so 复制文字并打开抖音 copies it for pasting. At least one photo is required;
with none selected, 准备图文 remains available with the edited text preserved.

发布到 B 站 opens `com.bilibili.bplus.followingpublish.FollowingPublishActivity` with action
`tv.danmaku.bili.intent.action.FOLLOWING_SHARE`, `content`, `share_publish=true`
and photos in `ClipData`. Bilibili 9.16.0 was verified to fill text and two photos
in the dynamic composer. Its existing composer can append text or ignore new photos,
so finish an existing draft first; this app does not clear it. 准备图文 stays available.

微信朋友圈 opens `com.tencent.mm.ui.tools.ShareToTimeLineUI` for text alone or one
photo, verified on WeChat 8.0.79. It uses `ACTION_SEND`, an image MIME type,
`Kdescription`, and either a single `EXTRA_STREAM` or `Ksnsupload_empty_img=true`.
With more photos selected, the same preview saves all of them to an album and copies
the text for manual posting. It never silently drops photos. 准备图文 also remains
available if WeChat opens but does not accept the content.
For Bilibili and WeChat, returning to this app keeps the edited preview and selection
available for that fallback.

Douyin, Bilibili and WeChat receive read-only grants for individual shared files,
so their internal transitions can still read them after the receiver closes. These
grants are revoked when the corresponding week-old share cache is cleaned up.
These entries are app-version dependent; unavailable or restricted entries leave
an error and the manual fallback with the edited text and photo selection intact.

飞书 opens 准备图文. Its share receivers send each photo and the text as separate
messages; in a Feishu chat, put the saved photos and pasted text in one rich-text post.
准备图文 is the manual fallback for WeChat Moments, Bilibili and other apps: edit a copy of the
text and select photos, save them to a separate `Pictures/这里分享-…` album, then copy
the text. It also works with just text or just photos. There is no platform photo
limit in this flow; all photos start selected. Files are numbered in their original
order, with insertion order and timestamps arranged for newest-first galleries.
The result shows the album name and count; the album stays on the phone for the user
to select in the destination app and remove when no longer needed. Only Android 9
asks for storage permission; newer Android versions use MediaStore without it.
A failed partial save removes the rows it created. Nothing publishes automatically;
only the optional Telegram and Threads assistants operate another app's UI. If sharing
to X, Telegram, Xiaohongshu, Threads, Douyin, Bilibili or WeChat fails, 准备图文 is offered with the edited text and
selection preserved.

On the home screen it is 这里 (here): a white cat with a grey cap split by a white blaze
and big amber eyes, peeking in from the lower left on blue as Shizuku's cat does, as an
adaptive icon with its own monochrome layer for themed icons. The blue is also the app's
own theme colour, the one used when neither the user nor the system picks another.

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

## Ledger

记账 is a ledger kept on this phone alone, in place of iCost: books of bills over accounts in
their own currencies, summed per book in its currency. Its bills are on the timeline; its
page, opened from 我 (or `moments://ledger`, on its sums), has three tabs: 账单, every bill of the book shown under its months and days with what
they spent and earned; 统计, the month, the year or all of it, picked from a menu on the period's name and stepped through with the arrows beside it:
what was spent, earned and left, the day's average and how it compares with the last
stretch's same days, then charts of spending or earning, drawn on a canvas, each mark read
out on a tap with its bills a button away: by day, month or year as bars; the running total
over the last month's or year's; a calendar of the month's days, or the year's as a grid,
deeper for more spent; the weekdays' averages; the categories as a ring over the list of
each one's share, the stretch's six biggest in colour-blind-safe hues and the rest as 其他;
and over a year or all of it, earning against spending and the categories stacked by month
or year; and 资产, the net assets, what is held and what is owed, their line at the end of
each of the last twelve months (balances taken back through the bills after it), and the
accounts by kind (cash, debit cards, credit, online wallets, stored value, investments,
loans, people lent to or borrowed from), each with its balance. The book's name, tapped, switches books, or
shows 全部账本, every book but the archived at once, summed in the chosen book's currency,
its categories summed by name across books and each bill marked with its book. The eye
beside the month's sums hides every amount as `¥****` (隐藏金额, kept until shown again,
here and on the Moments', trips' and places' pages), and the bar searches bills (note,
category, account, amount). 记账设置 is under 我.

A bill is an expense, an income or a transfer, the last also a repayment, a loan given or
taken, or one collected; or a refund or reimbursement of an expense, written from that
expense's page. `moments://new-bill`, or the ledger's +, opens a bill alone on its keypad; a
Moment with a bill is written from 记录 with 账单 added; changing a bill, or writing
one from a Moment, an account or a day, is a full-screen dialog of its own. Either has its amount
(sums like `12+3.4` add up) in any currency, its category and second-level category,
the account, or several accounts each with its share (one is needed to save it, and a
transfer needs one of its two sides, though an imported bill may have none), its time and book, an expense's
discount (on record only), a transfer's fee (an expense) and discount (an income) and,
between currencies, what arrived (the day's rate suggests it), a note, whether
it is to be reimbursed (left out of the sums until it is) or left out of the sums
altogether, and the Moment, place and trip it belongs to: the trip under way that day
unless another is picked. Its categories, accounts, currencies and places are listed with the
ones the latest 200 bills used most first. Laid out after iCost, its categories are a grid of
icons, a chosen one's second-level ones opening under its row, and the rest are chips; a bill on its
own (not under a Moment), on a window tall enough, is typed on a keypad pinned at the bottom, with
plus and minus and 保存, under its amount, time and note. A refund takes from its expense's category up to what was
spent, anything beyond it earned; a reimbursed expense counts only what wasn't paid back.
A bill's page shows all of this, its refunds and reimbursements, and where it leads: its
Moment, place, trip and day. A bill with no place of its own nor its Moment's is placed by the
footprints: the sample nearest its time, within a quarter of an hour and good to 100 m, named
by Photon as a new Moment's place is (the point alone goes out, nothing of the bill, and each
spot's name is kept in `footprints.sqlite` so it is asked once). Its page shows it as 足迹位置,
leading to its day, and its row in any list of bills after its note. A row in a list of bills never names its account, which only its page shows. An account's page has its bills and 校准余额, which sets the
balance to what it really is by moving its opening balance, adding no bill.

It meets the Moments where it should: a Moment's page lists its bills, and a meal's
Moment with a bill but none in the ledger offers 记一笔 with the amount, time, place and
the book's usual food category; a trip's page shows what it cost, with what all books
spent over its days beside it, and a day's and a place's page what was spent there,
each leading to those bills. 关联时刻和旅程 links a meal's Moment to the expense of its
day of the same amount, and a travel book's bills (one whose name says 旅) to the trip
under way.

记账设置 manages the books (each with its own categories, renamed, nested, merged or,
unused, deleted), the exchange rates, and the data. 从 iCost 导入 reads the .xlsx of
iCost's 账单导出 (the platform's zip and SAX, no spreadsheet library), picked here or
shared to 这里 (导入到记账) from iCost, WeChat or the file manager, or opened with the
app, which copies it in and comes forward here with what it found: every row type it
writes, the books, the accounts with their currencies and a kind guessed from their names,
split payments (`红包[-23.89]/中国银行[-0.01]`), discounts, fees and loans. The ledger
has no tags: an export's are put at the end of the note as `#tag`, as are an older backup's and
those of the bills already kept when tags went. The rows
iCost derives (a transfer's discount as 转账优惠/还款优惠, a refund's excess as 退款收入)
are folded back into their bill, and each refund and reimbursement is matched to its
expense by book, category, amount and time, the export naming none by id. An amount the
export has in another currency than its account's is converted at that day's rate. What
it found is shown before it is written, beside the ledger's bills (one already there is
skipped: the same row of an earlier export, or a bill written here, by hand or from 自动记账,
of its book, kind, amount and currency within ten minutes of it, whatever its note and account;
a refund or reimbursement new to a later export still finding its expense from
an earlier one) or in their place, so importing each newer export brings the ledger up to
date; a bill changed or deleted in iCost isn't. The export has no balances, nor anything
before the range it was made for, so each account is calibrated after. The rates are the European Central Bank's, bundled (`assets/ledger-rates.json`)
and brought up to date from Frankfurter only when asked, the one request the ledger
makes, with no bill in it; a rate of one's own can be set. 备份到文件 writes the whole
ledger as JSON, which 从备份恢复 reads back; 导出 CSV writes the bills with iCost's columns.

`ledger/LedgerStore` keeps it in `noBackupFilesDir/ledger.sqlite`, outside the API's sync
and Android's backup, read whole after each change. Bills are private: 我 → 记录项与公开
(`moments://items`) makes a book's new bills public, and 记一笔's 公开 chip says so for one
bill. A public expense or income publishes with 全部发布到网站 as what is public of it alone,
its kind, amount, currency, category and note (`data/store/Outside.kt`): a bill of a Moment as a part of
that Moment, any other as an entry of its own; one made private again is taken off the site.
发布更改 lists them under 账单; `ledger/ICostImport`,
`ledger/Accounting` (balances, sums, refunds) and `ledger/Links` are plain Kotlin with tests
in `LedgerTest`, which also reads a real export when `ICOST_EXPORT` names one. Not carried
over from iCost: recurring bills, installments, savings plans, templates and photos on
bills, none of which the export holds.

自动记账（试用）, switched on in 记账设置, has the share assistant's accessibility service
(named 分享与记账助手（试用）) read WeChat Pay's and Alipay's pages while no share is running,
the whole page, scrolled out of view or not. On one that says a payment went through
(支付成功, 交易成功), with an amount and nothing of money coming in or back, it pops up
记一笔 ¥9.90 with the note and the account it read. Each app's rows are taken as they are:
支付时间 is the time; Alipay's 付款方式 and WeChat's 支付方式 the account, matched by a card's
last four digits or bank, 零钱, 余额, 余额宝 or 花呗; Alipay's 商品说明 and the name over
WeChat's amount the note. Tapping it opens `moments://payment?amount=…&time=…&note=…`, the
bill's form with those and a category guessed from the note when it can be (the last bill with
the same shop in its note, else the book's category its words point to: 奶茶 饮料, 外卖 三餐,
滴滴 交通). Nothing is written until it is saved. A payment of the same amount within ten
minutes of a bill already kept isn't offered, nor one whose notification is still up; cleared,
it is offered when its page is next opened. It only reads: no tap, no text, nothing of the
page kept or sent. `ledger/Payments` reads the page's words, with fixtures in `PaymentsTest`
taken from `uiautomator dump`; `ledger/AutoBill` keeps the switch and posts the notification.

## Pages after the design system

我 holds the changes waiting (a block on the accent's soft tint, only while there are any),
this month's spending in figures with a bar for each day, and four rows: 账单与资产, 足迹,
记录项与公开 and 设置 (`moments://settings-home`, a row for each section). The timeline, the
ledger's bills and a trip's days open each day with a header row, its day of the month and
weekday, the ledger's ending with what the day came to and a trip's with 第 n 天, and the day's
Moments and bills run across the column under it; a month's heading says how many Moments it had and what it spent.
A day with footprints ends with a 轨迹 row, the day's line drawn where a bill's icon goes, how
long it moved and how far, and it opens the day; footprints alone don't put a day on the timeline.
A Moment's page lists its place, then its parts row by row, a charge among them, its trip and
day, and ends with whether it is public and which parts would publish, and, while it is only on
the phone or changed there, a row that says so and opens the changes; a bill's page has its 公开 switch. Writing a Moment, the
加一项 row opens 美食, 账单 and 类型. A day's page starts with that day's footprints on a map across its
top, the stretches solid and the gaps between them dotted, its Moments as dots and its bills as
rings where the footprints were when they were paid; under the day, how far it went, how long it
moved and what it spent, then the trip it was on and its Moments, bills and charges from the
morning on. A trip's page has 回放旅程
as its ink button, its distance, charging and spending in figures, and in its bar 编辑旅程
(`moments://trip-edit/<id>`): its name, days and whether it is public, kept on the phone and
published as a collection. A trip with a public span of footprints is drawn and played back
along it. The app reads the Moments and trips from `/v1` and, beside them, what only the second
shape has from `/v2/changes` (charges, public bills and marks, collections, public tracks).
The tabs stand down a rail on a window 840dp wide or more and on a phone on its side; on a wide
window the map's trips stand in a column at its start and a bottom panel opens as a dialog in
the middle. The timeline has no bar: its title, counts and search scroll away with it, the page under the
status bar behind a short fade, and the car's charges (from `/v2`) are rows on their days. The
filters are the search page's: what kind (everything, Moments, bills or charges, each with how
many) and when (a year, then a month); with nothing typed, a filter lists all it lets through, a
bill matching by its category and note. What it finds is listed on the page under its years, a
Moment by its photo, its words around the match, marked, and when and where. A Moment's page can make its
meal or its photos private on their own (`partVisibility`, written onto the entry's parts as
it publishes). 编辑旅程 also lists the Moments of its days and of two days either side, each
taken in or left out by a switch, and its photos to pick covers from in order.

## Local footprints

The footprints are private. A trip's page has 公开这段旅程的轨迹: on, a span following the
trip's days is kept (`track_shares` in `footprints.sqlite`) and, at the next 全部发布到网站, its
line goes out simplified to 30 m, a segment for each unbroken stretch, and the site draws the
trip's legs from it; off, it is taken off the site. 记录项与公开 counts the public spans.

The map tab shows local GPS footprints alongside its places and published trip routes,
using the same MapLibre map. 地图 → 足迹 opens a bottom panel for recording, choosing a
day or recorded trip, and exporting/deleting tracks, laid out as grouped
lists: a 记录 group of switches (日常自动记录, 单次行程, 在地图显示足迹) with pause or
resume between them, then a 轨迹 group under the day and trip filter chips. Closing it
keeps the selected track visible; tapping the selected track's row closes the panel and
fits that track on the main map. The layer can be hidden without stopping recording.
How recording behaves is set on its own page, 足迹设置 (`moments://footprint-settings`),
opened from the panel's settings button, which returns to the panel, and from Settings →
足迹, which also opens the map panel. Notifications open the map, and the older
`moments://footprints` link also opens it. 日常自动记录 stays on until turned off; 单次行程 marks a separate
trip, whose end leaves daily recording running if enabled. Both share the same samples.
暂停所有记录 pauses both without ending the trip. The panel shows days and recorded
trips, their distance, sample count and connected track time, and exports the selection
or all samples to GPX through the system document picker. Deleting requires daily
recording off and the trip ended, then confirmation; shared samples disappear from both
the day and trip views.

`footprints/FootprintStore` owns an indexed SQLite database at
`noBackupFilesDir/footprints.sqlite`, outside API sync, publishing and Android backup.
There are no API changes. Export before uninstalling; ordinary GPX exports use the same
refined track as the map and distance totals, with original WGS84 coordinates, timestamps
and altitude where available. 导出当前原始采样 GPX also includes uncertain and stationary
observations excluded from that track, each as an isolated point with accuracy (metres),
speed and speed uncertainty (metres/second), and its original recording segment in
`https://hyoban.cc/ns/footprints/1` extensions. It is for reanalysis, not a connected route.
The existing online
MapLibre basemap is reused, while collecting samples works without a connection.

The opt-in location foreground service needs precise location and shows an ongoing
notification with a pause action. Daily recording requests fixes every 5 seconds with no
provider distance filter, so stationary fixes can be used to stop GPS; the store still
filters stationary samples. At 15 seconds the phone's MediaTek GNSS powered down between
fixes (one satellite-status callback per fix, often under ten satellites used) and stayed in
place for minutes or jumped 100–250 m while walking, with speeds near zero; while it tracks
continuously it used 40–50 satellites at about 12 m. An active trip requests 5 seconds / 5 m. These are provider
hints, not guaranteed rates. On Android 12+, initial acquisition and stationary probes
use a system-enforced two-minute request duration, independent of the app timer. After
acquiring a usable fix, daily recording allows up to 60 seconds of delivery batching;
manual trips and acquisition are not batched. Providers may still deliver individual fixes.
Fresh delivery is measured before accuracy filtering, so weak but current fixes do not count
as a batching failure. Stale-only delivery for a full window, or 90 seconds without fresh
delivery, falls back to bounded ordinary acquisition without breaking the segment. Batching
retries after 10 minutes, with repeated failures backing off to 20, 40, then at most 60 minutes.
Only a good fix during active recording can trigger that retry; rest is never interrupted to
test batching. Three minutes of healthy batched delivery resets the backoff. The cooldown
lasts for the recording service's lifetime; older persistent OS-build blacklists are removed.
This guards devices/emulators whose GNSS batch implementation stops delivering fixes without
permanently penalizing a temporary signal outage. Enabling batching is not assumed to save
power or work correctly merely because the API accepts it.
A batch is ordered by monotonic sample time, checked against the current recording segment
and a 90-second age window, and written in one database transaction. Motion decisions use
sample times and examine the entire batch before resting. A mode change or manual pause
invalidates pending samples from the previous segment. Request replacement after acquisition
does not introduce a new track segment.
Fresh, valid observations are stored before track-quality filtering, including those over
100 m uncertainty and stationary jitter. Malformed, stale, out-of-order and obsolete-segment
callbacks are never stored. Schema version 2 adds nullable speed and speed-uncertainty
columns without changing old coordinates or recording state; samples discarded by version 1
cannot be recovered. No original is overwritten by refinement.

`TrackRefiner` derives the map and ordinary GPX deterministically from the stored samples.
It keeps a bounded window of candidates (30 seconds, at most 32 points), uses accumulated
displacement and path consistency to retain small walking steps and corners, and waits for
a following fix before committing a candidate. Stationary clouds are compressed to their
anchor. Uncertain spikes are checked against subsequent fixes and speed uncertainty;
a sustained relocation begins a new segment. Fixes over 50 m uncertainty break the displayed
route, as do restarts, pauses, mode changes, gaps over 45 seconds and movement inconsistent
with available speed evidence. Those breaks do not count toward distance or connected track
time. The unconfirmed tail stays in raw storage, so the displayed endpoint can lag behind
collection. These are conservative initial thresholds, not guarantees that a GPS position
is correct. Sparse old tracks may become disconnected rather than implying a shortcut.
Long tracks are thinned for display only; ordinary GPX retains all confirmed points and
raw GPX retains all stored samples. Sampling intervals and the daily rest policy are unchanged.

Daily recording confirms a stop with multiple spaced fixes over at least three minutes
without movement beyond the accuracy-aware anchor. Reliable speed also keeps recording
active when coordinates are noisy. Missing fixes alone do not confirm a stop: acquisition
and temporary signal loss get two minutes before GPS rests. The significant-motion wake-up
sensor and fresh, accurate passive location displacement can resume it. Passive observations
reuse locations already generated by the system; they do not request GPS fixes or Wi-Fi scans.
When GPS times out, a separate bounded low-power request tries the network provider,
then system fused if network is unavailable. These positions are only displacement evidence
against an existing anchor, never track points. Uncertain positions cannot wake recording;
GPS must still confirm the route. Android 12+ bounds this request to 20 seconds; older
versions use AndroidX’s bounded current-location request. A fallback GPS probe runs after two minutes; failed probes back off to four, eight, then ten
minutes, while detected motion resets the delay. A same-place probe needs three spaced fixes
over at least 30 seconds before resting again, rather than sleeping after its first fix.
Fallback timers may be deferred during deep sleep/Doze; no exact alarms or polling
accelerometer keep the phone awake. Departure can therefore still be missed initially.
State transitions and their reasons are logged under the Footprints tag without coordinates.
Each restart breaks the displayed route rather than drawing a line across the
unobserved gap. The notification and panel show 省电等待 while GPS is off. Manual trips
bypass this policy and keep continuous requests. Battery savings need measurement on
the target device; fewer stored points alone do not demonstrate lower GPS power use.

The recorder also keeps bounded local diagnostics in `noBackupFilesDir/footprint-diagnostics/`:
two rotating JSONL files of at most 1 MiB each, outside sync and backup. A bounded queue and
one IO worker keep disk writes out of location callbacks; dropped events and storage failures
are counted, and logging failures never stop recording. 足迹设置 → 诊断 → 导出诊断日志 exports the
retained events in order after draining queued writes. Records include wall-clock and elapsed
time, service run/request identifiers, app/OS version, permissions, battery/power-save state,
request durations and callback/stored-point counts, per-fix accuracy/age/speed and filter
decisions, timer delays, and sensor/passive wake-up events. While GPS runs, each fix also
carries the satellites used and visible, the mean C/N0 of the four strongest used, how many
used are on L5, and the status callbacks since the previous fix with their longest gap, which
shows whether the receiver tracks continuously or duty-cycles; the status callback starts no
fix itself. A rest's phase event records whether activity said moving, the time since the
last movement and which unmoving wait confirmed it (`stop_timeout_ms`). Fix timestamps can be matched
to the local track database. Coordinates, altitude, Wi-Fi/Bluetooth names, tokens and exception
messages are not logged. There are no diagnostic uploads, extra location polls or wake-up timers.
With `track_filter=window-v1`, `raw_delivery.stored_samples`, `fix.raw_stored` and the request's
`stored_fixes` count describe original observations, not confirmed display points. Refinement
runs when reading tracks and does not write live logs. With `track_diagnostics=1`, existing
location deliveries also carry a `recording_environment` snapshot at most once per minute
(battery, interactive/idle and power-save state), plus each fix's monotonic sample time and
the OS build on service start. These snapshots are opportunistic: no fixes means no extra
timer or wake-up, and battery percentages cannot attribute power use to this app alone.

Exporting diagnostics appends a streaming replay of the latest recorded day, even when
that day is older than the live log. The `track_replay_start` record names the day, scope,
filter version and principal thresholds. `track_decision` records explain pending windows,
confirmed points, quality rejection, expired candidates, spikes, relocation and unconfirmed
tails using fix timestamps, accuracy, speed, displacement and thresholds. `track_link_break`
uses the same connection rule as the map/GPX; `track_replay_summary` gives raw/display counts,
segments and connected distance/time. Reason counts are event counts, not mutually exclusive
sample totals. These rows have `mode=replay`: `time_ms` is export time, `fix_time_ms` identifies
the original sample, and `decided_at_fix_ms` identifies the confirming observation. They
describe the current filter's reconstruction, not what an older app drew at recording time.
The replay contains no coordinates or altitude and never changes stored samples. Only the
latest recorded day is included to bound the work; its rows are written directly to the
chosen export, so that file may exceed the rotating live log's 2 MiB cap. Deleting the
samples also removes the evidence needed for this replay.

The recorder's status-bar notification icon follows its live state: footprints for
valid fixes, a crosshair while waiting for a fix, and a warning triangle when system
location is off. Daily rest uses a separate minimum-importance, silent notification
channel so its status remains in the drawer without a status-bar icon. The same notification
ID returns to the active channel when GPS resumes; the foreground service and motion
sensor stay alive while resting. Pausing or stopping
removes the recorder notification. Connection automation uses a separate chain-link icon,
so their background watcher cannot be mistaken for active recording. Notification/icon
visibility still follows the user's system notification settings. Active notification channels
use normal importance without sound, vibration or badges. Existing channels retain their
settings; 足迹设置 → 状态栏显示 → 通知设置 opens the recorder's notification settings if an older
silent channel's icon is hidden. No global notification preference is changed.
On Android 16+, the same notification also requests a Live Update with short state text:
记录中, 定位中, or 定位关闭. GPS-free rest removes the Live Update, leaving its status in
the ordinary notification and footprint panel; resuming GPS requests promotion again.
This uses the platform promotion permission and
BigTextStyle, with no synthetic progress or periodic display refreshes. ColorOS replaces
ordinary small icons with the launcher icon, but its Fluid Cloud can show this live state
and the original state icon. Enable 流体云显示实时活动 through 足迹设置 → 状态栏显示 →
实时活动; if the system has no promotion settings activity, the link opens app details.
On the PMX110 running ColorOS 17, enabling this per-app setting was necessary: notification
channel visibility alone did not enable Fluid Cloud.
The separate rest channel was also verified on this device: the system raised the
foreground notification's importance to LOW, but hid its status-bar icon under its silent
notification policy. The drawer notification remained, GPS requests stopped, and the
significant-motion sensor stayed registered. Other devices or user overrides may still
show silent notification icons.
The status-bar options also inspect `canPostPromotedNotifications()` and the actual posted
notification’s `FLAG_PROMOTED_ONGOING`, with an explicit refresh button and a diagnostic event.
They distinguish disabled app notifications, disabled promotion, intentional rest, no active
notification, and a notification that the system has not promoted. No polling timer is added.
Other OEMs may apply additional Live Update restrictions; without promotion, the ordinary
notification remains available and its icon may still be replaced by the system.

Enabled recording resumes when the app opens. After a reboot it resumes only if
background location is allowed (Android 10 has a separate permission button; newer
versions use the app's system settings). Force stopping requires reopening the app.
OEM battery restrictions can still interrupt recording; battery use and long-running
reliability must be measured on the target phone. Road matching and importing other apps’
tracks are not supported.

An optional 活动识别增强 control uses Google Play services activity transitions for walking,
running, cycling, vehicle travel and stillness. It starts disabled, requests physical activity
permission on Android 10+, and operates only during daily recording. Entering movement can
wake a resting recorder. Until it reports stillness again, unmoving fixes need 10 minutes
rather than three to rest GPS. On the target phone it reported nothing during walks that
began when leaving home Wi‑Fi, so neither the sampling rate nor resting depends on it. Stillness is advisory and cannot pause a manual trip. Missing Play
services, denied permissions or registration failures leave native motion/passive detection
available. Activity events never opt the user into recording or resume a manually paused
recorder. Its PendingIntent remains registered while the saved daily selection is active,
even if the recording service is lost. A fresh movement event can restart that service with
precise and background location access; service delivery rechecks the saved state and
permissions so a later pause or manual trip wins. A queued movement followed by stillness
does not start GPS. Android/vendor background-start restrictions still apply, and force-stop
requires reopening the app. The app does not send tracks to its API or Google’s fused-location client.

### Connection automation

足迹设置 → 连接自动控制 lets the user select Wi-Fi names from the current connection,
cached system results and saved choices, and Bluetooth devices from the paired list.
Nothing is selected or enabled by default. Joining selected Wi-Fi automatically pauses;
leaving all selected Wi-Fi starts recording. Connecting selected Bluetooth starts recording;
disconnecting the last selected device pauses it. Bluetooth takes priority: Wi-Fi cannot
pause while a selected Bluetooth device remains connected. Simultaneous disconnections
also follow the Bluetooth pause rule. Resolve priority before checking the recording state
so opposing rules cannot alternate depending on whether recording is already active. Wi-Fi networks with
the same SSID are treated as one. Pairing and connecting happen in system settings.

Changes settle for five seconds and then execute without a confirmation notification.
Starting resumes a paused trip/daily recorder, or enables daily recording when both are off.
There is no notification cooldown to discard subsequent genuine connections. Initial snapshots
only seed connection state, so startup does not undo a manual choice. Manual recording changes
cancel pending events; an atomic state comparison also prevents a late action from overwriting
a newer manual choice. Rules resume on the next connection change. Rules and connection
state are checked again before execution, and diagnostics log the kind/action/result without
Wi-Fi names or Bluetooth addresses. Old confirmation notifications are cleared on upgrade.

An opt-in `specialUse` foreground service watches system connection callbacks and
Bluetooth broadcasts, without requesting GPS fixes or actively scanning. Its separate
ongoing notification keeps it available when recording is paused.
On Android 12+, its permanent network callback excludes location-sensitive information.
A new Wi-Fi connection opens a short SSID lookup, removed as soon as identities are known
or after ten seconds. Known connections retain their names until disconnection; signal
strength and validation changes do not trigger more location access. Unknown identities
remain unknown until reconnection or watcher reconfiguration, without polling or treating
them as departures. Bluetooth rules continue while identity is pending, and manual choices
still win. Earlier Android releases retain the legacy network callback. SSID lookups can
appear in the system's location access history even though they request no GPS fixes.

Automatic recording
requires precise and background location permission and system location enabled, including
Bluetooth-only rules, so a background start can access location;
paired Bluetooth access requires Nearby devices on Android 12+. Notifications must be
allowed. If the OS rejects a recording start, the watcher directs the user to the footprint
panel to check permissions and resume. Settings retain the existing path
`noBackupFilesDir/connection-reminders.json`, with no API
sync. Enabled monitoring resumes on app launch or boot; force-stop requires reopening
the app, and vendor battery restrictions can still interrupt it.

On Android 16+ devices with companion-device setup, each selected Bluetooth device can
optionally be associated with the app using the system confirmation UI. Android’s
`CompanionDeviceService` can then wake the monitor when that device connects or disconnects.
BLE proximity events are ignored. The watcher still applies the same five-second debounce,
Bluetooth priority and manual override rules. Associations can be removed independently
of the ordinary paired-device selection. Turning automation off stops presence observation.
A local, backup-excluded `companion-connections.json` stores only association IDs and last
connection states, so an initial/repeated system snapshot does not undo a manual choice.
Android 16 QPR2 (API 36.1) and newer also seed Bluetooth state using the public per-transport `isConnected()` API;
older versions use profile snapshots. Background-start rejection, missing permissions and
other service failures have separate diagnostics and user messages.

## Building

Android Studio opens `apps/android` as a project. From a shell, with the Android SDK
in `local.properties` (`sdk.dir=…`) and a JDK 17 or newer:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`RecordingRecoveryTest` under `src/androidTest` checks service recovery, pause races and fresh
but inaccurate batch delivery on an emulator. It skips physical devices and takes about a
minute. Build with `:app:assembleDebugAndroidTest`, then install both APKs on the emulator
and run it explicitly (replace the serial if needed):

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class cc.hyoban.moments.footprints.RecordingRecoveryTest cc.hyoban.moments.test/androidx.test.runner.AndroidJUnitRunner
```

These tests grant recording permissions and temporarily mock GPS, restoring recording settings
and removing the mock provider afterward. They simulate the background-start exemption for
the service recovery test; physical Activity Recognition events and OEM restrictions still
need device testing.

The maps are OpenFreeMap's vector tiles drawn by MapLibre, which need no key.

The API, the origin the photos and videos come from and the map's light and dark styles
are the build's (`BuildConfig`) until changed under 我 (时刻与数据 and 地图与足迹); a new API
drops the copy on the phone and reads everything again. 外观 there chooses light, dark or
the system's; the colours themselves are the design system's, the same for everyone.

The unit tests round-trip every record under the repository's `data/` through the
app's models, so the app and the site
can't drift apart without a test noticing; they also check the media URLs against the
site's rules, which trip a Moment is on, and the sync against a stand-in for the API.

## On the phone, from adb

Every page has a link, `moments://moments`, `map`, `trips`, `settings`,
`new`, `moment/{uid}`, `trip/{id}`, `play/{id}` (a trip played back), `day/{YYYY-MM-DD}` and `place/{id}`, which
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
Moment's and its description;
the months and days heading a list as headings; the theme's choices as a group with the
one chosen.

## Layout

- `data/spec` — the records as Kotlin, each keeping the fields it doesn't know.
- `data/api` — the API's change feed, the Moments with the places and trips that changed, and its writes.
- `data/store` — the copy on the phone, and the repository that brings it up to date
  and gives the screens what the site shows (no hidden Moments, and none about tech
  unless 显示技术时刻 is on in the settings).
- `data/local` — the Moments written on the phone: ULIDs, their photos' time and place,
  where the phone is and what the place there is called.
- `data/media` — photo, poster and video URLs, picked as the site picks them, or the
  phone's own copy of a photo not yet published.
- `data/trips` — which trip a Moment is on, a trip's days and its stops in order, and its playback: where along the route each Moment is reached and how long each card plays.
- `widget` — the home screen's widgets (Glance) and what they pick for the day.
- `ui` — Compose screens: the map, the Moments, a Moment's page, a new Moment, the trips, a trip, the settings.

The app's own `data/` directory from when it recorded Moments is left on the phone as
it was.

## Thanks

The cat on the icon is 馒头 (Mantou), drawn from a photo of the real one. The settings
page says so too, with a photo of its face.
