# Cloudflare OAuth and automatic Worker deployment

RKRemote uses Cloudflare OAuth Authorization Code with PKCE. The Android app never asks for the Cloudflare password, Global API Key, or an API token. Access and refresh tokens are encrypted with AES-GCM using a non-exportable Android Keystore key.

## One-time OAuth client registration

A Cloudflare account administrator must register the mobile OAuth client once. Cloudflare does not allow a third-party app to create its own OAuth client without the account's authorization and required permissions.

1. Sign in to Cloudflare Dashboard.
2. Open **Manage Account → OAuth clients → Create client**.
3. Name: **RKRemote Android**.
4. Response type: **Code**.
5. Grant types: **Authorization Code** and **Refresh Token**.
6. Token endpoint authentication: **None** (public native app; no client secret).
7. Exact redirect URI: **com.rkremote.app:/oauth2redirect**
8. Scopes:
   - account.read
   - workers-platform.read
   - workers-platform.write
9. Keep visibility **Private** if this is only for the account owner and its members. A public app for unrelated Cloudflare users needs Cloudflare's public-client requirements and verified client URL domain.
10. Save the **Client ID**. Do not put a Client Secret in the Android app.

Cloudflare's supported native-app flow is Authorization Code with PKCE S256. Cloudflare explicitly does not support embedding a client secret in a mobile app. See the official OAuth client guide:
https://developers.cloudflare.com/fundamentals/oauth/create-an-oauth-client/

## Configure the Android build

Set the public Client ID in the root gradle.properties:

CLOUDFLARE_OAUTH_CLIENT_ID=your-client-id

Or pass it to Gradle as -PCLOUDFLARE_OAUTH_CLIENT_ID=your-client-id. The Client ID is not a password or secret. Never add a client secret, API token, or account password to this file.

## What the app does after login

1. Opens Cloudflare's authorization page in the system browser / Custom Tab.
2. Uses AppAuth to generate random state and a PKCE verifier/challenge (S256).
3. Exchanges the returned authorization code without a client secret.
4. Stores the access and refresh tokens AES-GCM encrypted with Android Keystore.
5. Lists the accounts the user authorized. If there is one account, it deploys automatically; if there are multiple, the user chooses one.
6. Uploads the Worker module using Cloudflare's multipart Worker Script API, creates the SignalRoom SQLite Durable Object migration if it has not already been applied, and binds SIGNAL_ROOMS.
7. Enables workers.dev and retrieves the account subdomain. If the account has not selected one yet, the app requests a generated rkremote-XXXXXXXX subdomain.
8. Saves the resulting WSS URL into the only connection field in RKRemote.

The script name is rkremote-signaling, matching cloudflare/worker/wrangler.toml. The source is bundled in app/src/main/assets/cloudflare/worker.js and mirrored at cloudflare/worker/src/index.js.

## Permissions and impact

The OAuth client needs account.read and Workers Platform read/write scopes so the app can list the authorized account, upload/update the Worker, configure its Durable Object binding and enable workers.dev. The first deployment creates a Worker and may set an account-level workers.dev subdomain if none exists. This is a real change to the selected Cloudflare account; only authorize the app if you intend it.

The app does not request DNS, zone, billing, user-management, or account-password access. Revoke the OAuth grant from Cloudflare if you no longer want RKRemote to manage the Worker.

## Current limitations

- A Client ID must be registered once by the Cloudflare account administrator and supplied to the Android build. This cannot safely be guessed or generated inside an unregistered mobile app.
- The first deploy requires a valid Cloudflare account with Workers/Durable Objects available and the required OAuth scopes.
- OAuth/API deployment has not yet been run against a real account. Verify the first deployment and resulting WSS endpoint before using the relay.
- This feature has not been independently security-audited.
