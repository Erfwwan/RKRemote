# Cloudflare manual Worker setup for RKRemote

Each RKRemote user deploys a separate Worker into their own Cloudflare account. There is no shared Cloudflare account, shared token, OAuth Client ID, or app-wide credential.

## 1. Deploy your own Worker

1. Open the [Deploy to Cloudflare](https://deploy.workers.cloudflare.com/?url=https://github.com/Erfwwan/RKRemote/tree/feature/remote-agent-foundation/cloudflare/worker) link.
2. Sign in to your Cloudflare account.
3. Review the project and deploy it. Approve the `SignalRoom` Durable Object binding/migration when Cloudflare requests it.
4. After deployment, copy the assigned `workers.dev` hostname.
5. In RKRemote, enter the Worker address in this form:

```
wss://rkremote-signaling.<your-workers-subdomain>.workers.dev
```

The exact hostname is assigned by Cloudflare and differs for each account. Do not copy another user's address.

## 2. Normal connection does not need an API Token

After the Worker is deployed and its WSS address is entered, both phones can use the relay. **Do not create or enter an API Token just to connect.**

The optional token field is only for updating the code of an already-deployed `rkremote-signaling` Worker from inside the app.

## 3. Optional: create a narrowly scoped API Token

Only if you want in-app Worker updates:

1. Open [Cloudflare API Tokens](https://dash.cloudflare.com/profile/api-tokens).
2. Create a new custom token for RKRemote. Do not use the Global API Key.
3. Grant **Workers Editor** access, scoped to the existing `rkremote-signaling` Worker only. Do not grant account-wide Admin or unrelated permissions.
4. Copy the token once and enter it in RKRemote.
5. Find the Account ID on your Cloudflare account's Overview page and enter it in RKRemote.
6. Tap **Save token securely**, then **Update Worker with API Token**.

The app first checks that the named Worker already exists, then uploads the app's current Worker module to that script. It does not enumerate your accounts, create a new Worker, or change your account-level `workers.dev` subdomain.

The token is stored locally using AES-GCM encryption with an Android Keystore key. It is not sent to the paired phone. Clearing the token in RKRemote removes the local copy only; to invalidate it, also revoke it in Cloudflare.

## Security and operational notes

- Never share your API Token, Global API Key, or Cloudflare password with anyone.
- Keep the API Token scoped to the one existing Worker and revoke it when you no longer need in-app updates.
- Normal relay operation uses only the Worker WSS address; the token is not part of peer pairing or encrypted relay traffic.
- Cloudflare can observe IP addresses, room IDs, timing, frame sizes and traffic volume, but cannot read the Noise-encrypted app payloads.
- All app traffic passes through Cloudflare and is subject to its Workers/Durable Objects limits and pricing.
- The deployment and token-update paths have not yet been tested against a real Cloudflare account in this source milestone.
