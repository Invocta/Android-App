# Invocta Android plugin SDK

This Android library lets an app expose a small set of local actions to Invocta. The two apps communicate through a bound Android service and `Messenger`; no server, API key, accessibility service, or shared database is involved. The SDK is a separate Gradle module and can be extracted into its own repository or published as an AAR later.

## Provider app

Add `implementation(project(":plugin-sdk"))` (or the published AAR when available). Subclass `InvoctaPluginService`:

```kotlin
class MyPluginService : InvoctaPluginService() {
    override fun isCallerAllowed(uid: Int): Boolean =
        userEnabledIntegration &&
            packageManager.getPackagesForUid(uid)?.contains("com.invocta") == true

    override fun actions() = listOf(
        PluginAction(
            "find_item",
            "Find an item in this app.",
            JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")
        )
    )

    override fun executeAction(id: String, input: JSONObject): JSONObject {
        require(id == "find_item")
        val query = input.getString("query")
        return JSONObject().put("match", findItem(query))
    }
}
```

Declare the service:

```xml
<service android:name=".MyPluginService" android:exported="true">
    <intent-filter>
        <action android:name="com.invocta.action.PLUGIN" />
    </intent-filter>
</service>
```

The SDK **denies all callers by default**. A host app must present its own opt-in setting and implement `isCallerAllowed(uid)`. The UID comes from Android's `Message.sendingUid`, not a caller-supplied JSON field. The example checks the Invocta package; apps that expose personal data or costly actions should also verify Invocta's signing certificate and ask for in-app confirmation before committing an irreversible action. Validate every input in `executeAction`; the advertised JSON schema guides the assistant but does not validate requests.

Keep actions fast and local. Requests time out after five seconds and JSON messages are limited to 8,192 characters. Return a JSON object with the result. Do not expose checkout, payment, messaging, or account changes without an app-owned confirmation flow.

## Invocta side

Invocta declares package visibility for `com.invocta.action.PLUGIN`. The user must approve each provider in **Invocta Settings → Connected apps**; that approval is tied to the provider's signing certificate. It exposes `discover_app_actions` and `call_app_action` to the on-device model. Discovery gives the app label, a provider ID, and the action schemas. The call uses that provider ID and action ID. The host app remains responsible for its own authorization and execution.

## Sample

`sample-food-app` is an installable demo. Open it and enable **Allow Invocta**, then enable **Sample Food** in Invocta's **Connected apps** settings. Ask Invocta to search the Sample Food menu, add a Veg Burger to the cart, or show the cart. The demo has no checkout or payment path.

```sh
./gradlew :app:assembleDebug :sample-food-app:assembleDebug
```

Install the two APKs from `app/build/outputs/apk/debug/` and `sample-food-app/build/outputs/apk/debug/` on the same device. The SDK is a local project dependency in this prototype; no artifact has been published.
