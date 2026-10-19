# Xitter Patches

Morphe patches that turn X's Android app (`com.twitter.android` 12.19.1-release.0) into a
client of a [Xitter](https://github.com/Xittered/xitter) instance.

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.1.0-dev.10](https://github.com/Xittered/xitter-morphe/releases/tag/v1.1.0-dev.10)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;2 patches total
<details open>
<summary>📦 X&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 12.19.1-release.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Change package name](#change-package-name) | Changes the app's package name, account type and name, so the patched app installs next to the original X app instead of replacing it. Do not also select Morphe's own "Change package name" patch. | • Package name<br>• App name |
| [Xitter](#xitter) | Makes the app a client of a Xitter instance. Every address of X (x.com, twitter.com, twimg.com, t.co...) is sent to the instance instead, signed in with the instance's sessions, and the app's sign-in screens sign in to the instance. Nothing is sent to X. | • Instance address<br>• Client app token<br>• Block third-party services<br>• Allowed hosts |

</details>

<!-- PATCHES_END -->

### How nothing reaches X

The guard is in several layers, so one missed path is still caught by the next:

1. **OkHttp**: every client goes through `OkHttpClient(Builder)`. That constructor is hooked
   to add an interceptor (rewrite and auth), a DNS and proxy selector that refuse X's hosts
   (they also catch redirects), no cookie jar, and system TLS in place of X's pinned CAs.
2. **`java.net`**: every `URL.openConnection`/`openStream` and `InetAddress.getByName`/`getAllByName`
   call in the app (166 call sites in 12.19.1) goes through the guard.
3. **WebView**: `loadUrl`/`postUrl` calls are rewritten or blocked.

No native library in the app contains X hostnames, and the app has no Cronet.

With `blockThirdParty` on (the default), Google/Firebase, Braze, Datadog, Stripe and other SDKs
are refused too; only the instance, `allowedHosts`, and localhost/LAN addresses are reachable.

### Signing in

The username → password screens are X's native sign-in screens, driven by the extension. The
password is checked with the instance's `POST /login`. The session (`auth_token` + `ct0`) becomes
the account's OAuth token, so several accounts work side by side. Accounts with two-factor
sign-in can't sign in this way yet (`/login` refuses them).

## Use

- **Morphe Manager**: [add this repository as a patch source](https://morphe.software/add-source?github=jakeayy/xitter-morphe)
  (or a built `patches-*.mpp`), pick the X 12.19.1 APKM from APKMirror, and set the instance
  address in the Xitter patch's options.
- **CLI**:
  ```
  java -jar morphe-desktop-all.jar patch --patches=patches-1.0.0.mpp -e Xitter -O serverUrl=https://your.instance -e "Change package name" --striplibs=arm64-v8a x-12.19.1.apkm
  ```

## Build

Needs JDK 21, the Android SDK (`local.properties` or `ANDROID_HOME`) and a GitHub token with
`read:packages` (for Morphe's Gradle plugin):

```
GITHUB_ACTOR=<you> GITHUB_TOKEN=$(gh auth token) ./gradlew :patches:buildAndroid
```

Output: `patches/build/libs/patches-*.mpp`.

## How it talks to Xitter

X's Android app speaks its own dialect of X's API; Xitter answers the web client's. The extension
translates on the device, so the instance needs no changes:

- **Operations**: Android GraphQL operations are sent as the web's (e.g. `UserWithProfileTweetsQueryV2`
  → `UserTweets`), variables renamed, ids sent as strings, results moved to where the app reads
  them (`Operations.java`).
- **Shapes**: typed objects get `__typename` first, Android's field aliases (`content`,
  `tweetResult`, `userResult`, `ownerResult`), users a `legacy` record (`Shapes.java`).
- **Older REST screens**: notifications and search are answered from the web's GraphQL
  timelines in the old `globalObjects` format (`V2Timelines.java`); followers/following from an
  Apollo-shaped timeline (`Connections.java`, `ApolloTimelines.java`).
- **Answered locally**: app/guest tokens, Play Integrity and key attestation, feature switches,
  analytics.
- **Classic UI**: X Lite (X's newer UI, Apollo-only) is switched off, and the Jetfuel welcome
  screen replaced by the classic sign-in.

## Status

Tested on a device against an unmodified Xitter server: sign-in, home (For you, Following),
profiles (posts, replies, media, likes, followers, following), editing your profile (with the
instance's pride flag picker under Website; flags show next to names in posts and profiles), follow and
unfollow, post detail, posting and replies, photo posts, reposts and quotes, likes, bookmarks,
notifications, search (top, latest, people, media), Explore (trends), lists, communities
(explore, community pages, join and leave), chat (end-to-end encrypted, with passcode backup on
the instance's Juicebox realm; messages go both ways live with the web client), blocked and
muted accounts, muted words, settings
pages X shows as web pages (they open the instance's, signed in), links (in-app or to the
instance; X pages are never loaded).

Not working yet: voice and video calls (the instance doesn't offer them), push notifications (they
need Google's push service, which the patches block). Accounts with two-factor sign-in can't sign
in from the app.

## Debugging

```
adb shell setprop log.tag.Xitter DEBUG        # each request, its auth and status
adb shell setprop log.tag.XitterBody DEBUG    # request and response bodies
adb shell setprop log.tag.XitterErrors DEBUG  # errors X's app recovers from and would report
adb logcat -s Xitter XitterBody XitterErrors
```
