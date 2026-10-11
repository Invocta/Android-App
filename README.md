# Invocta

**An open-source Android assistant you can use locally, sync across devices, and connect to your own AI provider.**

Invocta brings chat and device assistance into one Android app. You can keep chat history on your phone without creating an account, or sign in to sync it through Supabase. For AI, you can download Gemma 4 to run on your device or bring an API key for Google AI Studio or OpenAI. The selected AI provider and chat storage mode are independent choices.

Invocta can also work with apps that explicitly expose actions through its Android plugin SDK. The repository includes the SDK and a sample food app that demonstrates menu search and a local cart without placing orders.

## What the app includes

- **Local or synced chats.** Local mode stores sessions and messages in Room on the device. Signing in enables Supabase sync, including an option to copy existing local chats to the account. You can start locally and register later.
- **On-device AI.** Download and select the Gemma 4 E2B IT LiteRT-LM model in Settings. The app shows download progress, supports cancellation and retry, and checks that the model can initialize before marking it ready.
- **Bring your own key.** Select Google AI Studio (`gemma-4-26b-a4b-it`) or OpenAI (`gpt-6-luna`) after saving your API key in Settings. Keys are stored in encrypted Android preferences and excluded from app backup.
- **Device assistance.** The assistant can use app-side tools for supported Android actions, screen inspection, and navigation. Accessibility and other device permissions are requested separately; a model's proposed tool call does not bypass app-side permission handling.
- **App action plugins.** Other Android apps can offer structured actions through a bound service. Users approve each connected app in Invocta, and the provider app must independently allow Invocta to call it.
- **Voice input and output.** Local mode uses Android speech services. Signed-in cloud mode can use the existing Invocta speech server when available, with Android speech services as a fallback.

## Understand where data goes

Chat storage and AI processing are separate settings:

| Choice | What happens |
| --- | --- |
| Local chat storage | Sessions and messages are stored in the app's local Room database. They are not synced to Supabase while this mode is active. |
| Cloud chat storage | Authenticated sessions and messages are stored in Supabase. The included SQL migration enables row-level security so each signed-in user can access only their own chat rows. |
| On-device Gemma | Inference runs through LiteRT-LM on the phone after the model is downloaded. Downloading the model requires a network connection. |
| Google AI Studio or OpenAI BYOK | The selected provider receives the chat request. Depending on the task, that request can contain messages, images, screen details, and device-tool results, even when chat storage is local. API usage may incur provider charges. |
| Speech | Local mode uses Android speech services. Cloud mode can send speech to the existing Invocta server when available; that service is separate from chat AI and Supabase sync. |

The OpenAI client sends Responses API requests with `store: false`. API keys are never required in source files or `local.properties`; enter them in the app's Settings screen. A Supabase client key is included in the Android build for authentication and sync, so the database's row-level policies remain essential.

## Repository layout

| Path | Purpose |
| --- | --- |
| [`app/`](app/) | Android app, Jetpack Compose UI, chat storage, AI clients, speech, assistant overlay, and device tools. |
| [`plugin-sdk/`](plugin-sdk/) | Android library for apps that want to expose approved actions to Invocta. See the [SDK guide](plugin-sdk/README.md). |
| [`sample-food-app/`](sample-food-app/) | Installable example of a plugin provider with menu search and a local cart. |
| [`supabase/migrations/`](supabase/migrations/) | Chat tables, indexes, grants, and row-level security policies. |
| [`scripts/`](scripts/) | Optional development kit scripts for preparing an APK and a separately downloaded Gemma model. |

## Build and run

### Requirements

- Android Studio with the Android SDK for **API 37** installed. The app compiles with API 37, targets API 35, and supports Android API 24 and newer.
- A JDK compatible with the checked-in Gradle wrapper and Android Gradle Plugin. Use the project's Gradle wrapper rather than a system Gradle installation.
- An Android device or emulator for the app. On-device Gemma is a **2.58 GB** download and is recommended for devices with **at least 8 GB RAM**; actual compatibility and speed depend on the device.
- A Supabase project URL and client-side anon key for this build. The authentication component currently initializes even if you intend to choose local chat mode.

### 1. Clone and configure

```sh
git clone https://github.com/Invocta/Android-App.git
cd Android-App
```

Open the project in Android Studio and let it create `local.properties` with your Android SDK path. Add the following values to that **ignored** file:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_ANON_KEY=YOUR_SUPABASE_ANON_KEY
```

For Google sign-in, also set `GOOGLE_WEB_CLIENT_ID` to the OAuth web client ID configured for your Supabase Google provider. The app handles the `invocta://auth-callback` redirect. Email authentication can be used without Google sign-in.

`INVOCTA_API_URL` is optional and points to the existing speech server's `/api` endpoint. It does **not** serve chat AI or Supabase data. The app currently has a default server URL in its Gradle configuration; set this property if you operate a different speech server.

Do not put OpenAI or Google AI Studio API keys in `local.properties`, source code, or Git. Enter those keys in **Settings → OpenAI (BYOK)** or **Settings → Google AI Studio (BYOK)** on the device.

### 2. Set up cloud chat storage

For sign-in and sync, configure your Supabase project's authentication providers and apply the checked-in migration:

```sh
supabase link --project-ref YOUR_PROJECT_REF
supabase db push
```

Review the migration in [`supabase/migrations/20261011000100_chat_storage.sql`](supabase/migrations/20261011000100_chat_storage.sql) before applying it to a project that already contains chat tables. The migration creates `chat_sessions` and `chat_messages`, enables row-level security, and grants access to authenticated users only through owner-scoped policies. The SQL file being in this repository does not mean it has already been deployed to your Supabase project.

### 3. Build and install

```sh
./gradlew :app:assembleDebug :sample-food-app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The sample app is optional. To try plugin actions, also install `sample-food-app/build/outputs/apk/debug/sample-food-app-debug.apk`, enable **Allow Invocta** in the sample app, then enable **Sample Food** under **Invocta Settings → Connected apps**.

On first launch, choose local use or sign in. In **Settings → On-device AI**, download and select Gemma; alternatively, save and select a BYOK provider. The APK does not contain the Gemma model.

### Optional: prepare a device kit with Gemma

`scripts/prepare-dev-kit.sh` downloads the pinned Gemma model, checks its size and SHA-256, builds the debug APK, and creates an ignored `dev-kit/` folder. Its installer copies the model to a connected device without bundling the 2.58 GB file into the APK. See the [development kit guide](scripts/dev-kit-README.md) for the model revision, checksum, storage requirements, and install steps. Model binaries and generated APKs are not committed to Git.

## Build an app action plugin

The [`plugin-sdk`](plugin-sdk/README.md) module provides `InvoctaPluginService`, `PluginAction`, and a client protocol built on Android's bound-service `Messenger`. A provider declares its service, describes the actions it offers, validates inputs, and decides whether the caller is allowed. Invocta discovers providers and exposes approved actions to its AI tool loop as `discover_app_actions` and `call_app_action`.

The sample food app shows the complete flow. Its actions search a menu, add items to a **local** cart, and view that cart. It has no checkout or payment action. Other apps can add the [published SDK](plugin-sdk/README.md#add-the-sdk). For sensitive or irreversible actions, provider apps should verify the caller's signing certificate and require their own confirmation before acting.

## Current boundaries

- There is no Invocta-hosted subscription model in this repository. AI choices are the downloaded Gemma model or a user's own Google AI Studio or OpenAI key.
- Chat AI no longer depends on the former Azure chat server. The server-backed speech path remains for signed-in cloud mode when available.
- Device actions depend on Android permissions, accessibility information, the current screen, and each app's integration. The assistant may need to inspect the screen or ask for permission before it can complete a task.
- On-device model support and performance vary by hardware. The stated 8 GB RAM figure is guidance for the Gemma artifact, not a guarantee on every device.

## Contributing and license

Issues and pull requests are welcome. Keep changes focused, explain the device or API behavior they affect, and never commit API keys, signing credentials, generated APKs, or model binaries. For SDK changes, update the [provider guide](plugin-sdk/README.md) and sample app when their usage changes.

Invocta is licensed under the **GNU General Public License v3.0 or later**. See [LICENSE](LICENSE). The separately downloaded Gemma model has its own license; its notice is included with the [development kit scripts](scripts/model-LICENSE-APACHE-2.0.txt).
