# Xitter Patches

Morphe patches that turn X's Android app (`com.twitter.android` 12.19.1-release.0) into a
client of a [Xitter](https://github.com/Xittered/xitter) instance.

## Patches

| Patch | What it does | Options |
| --- | --- | --- |
| **Xitter** | Sends every X address (x.com, twitter.com, twimg.com, t.co, pscp.tv…) to the instance, translates X's OAuth signing into Xitter's session cookie + csrf header, and answers the app's sign-in screens with the instance's own sign-in. | `serverUrl` (required), `clientToken`, `blockThirdParty` (default on), `allowedHosts` |
| **Change package name** | Renames the package, its providers, permissions and account type so the app installs next to the real X. | `packageName` (default `com.twitter.android.xitter`), `appName` (default `Xitter`) |

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

## Limits

The app side is complete, but **the Xitter server answers the web client's API, not the
Android app's**. Of the 319 GraphQL operations X Android 12.19.1 uses, 21 share a name with
Xitter's handlers (HomeTimeline, CreateTweet, FavoriteTweet, SearchTimeline, …). Profiles,
post detail and most lists use Android-only operations (`UserResultByScreenNameQuery`,
`ConversationTimelineV2`, `UserWithProfileTweetsQueryV2`, `ViewerUserQuery`, …). Unhandled
calls get an empty `{data:{}}`, and Xitter lists them at `GET /_debug/unknown`. Those screens
need Android aliases on the server.
