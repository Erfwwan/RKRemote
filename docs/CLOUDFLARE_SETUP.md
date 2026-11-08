# RKRemote Cloudflare setup (GitHub Actions)

This is the recommended deployment path. **You do not connect or link your GitHub account inside Cloudflare.** GitHub Actions reads the Worker source already stored in this repository and publishes it to the Cloudflare account whose credentials you provide as repository secrets.

## One-time setup

1. Open your Cloudflare dashboard and create an API Token for deploying Workers. Use the narrowest permissions Cloudflare offers for editing Workers scripts and reading the account settings needed by Wrangler. Do not use your Global API Key.
2. Copy your Cloudflare **Account ID** from the account overview.
3. Open the RKRemote GitHub repository and go to **Settings → Secrets and variables → Actions**.
4. Add these two **Repository secrets** (not Variables):
   - `CLOUDFLARE_API_TOKEN` — the token created in step 1.
   - `CLOUDFLARE_ACCOUNT_ID` — your Cloudflare Account ID.
5. Open **Actions → Deploy Cloudflare Worker → Run workflow** and run it on `feature/remote-agent-foundation`.

The first successful deployment creates or updates the Worker and its Durable Object binding/migration from the checked-in `cloudflare/worker/wrangler.toml` configuration. Review and approve any migration prompt if Cloudflare requests one.

## After setup

- The workflow runs automatically whenever files under `cloudflare/worker/` change on `feature/remote-agent-foundation`.
- You can also run it manually from the Actions tab whenever you want to redeploy.
- The workflow summary shows the deployed Worker address when Wrangler reports it. Use the WebSocket form in RKRemote: `wss://<worker-name>.<your-workers-subdomain>.workers.dev`.
- You do not need to upload source files manually, install Wrangler, use Termux, or connect GitHub to Cloudflare.
- The Cloudflare token remains in GitHub Secrets and is never added to the APK, source files, workflow logs, or the paired phone.

## If deployment fails

- **Missing secret:** check the exact secret names above. GitHub does not expose secret values after saving.
- **Authentication/permission error:** create a new Cloudflare API Token with the required Workers deployment permissions and replace the repository secret.
- **Wrong account:** verify that `CLOUDFLARE_ACCOUNT_ID` belongs to the Cloudflare account where you want the Worker.
- **Durable Object migration error:** inspect the Wrangler error and the migration history in `cloudflare/worker/wrangler.toml`; do not delete or rename the existing Durable Object class casually, because existing rooms/sessions may be interrupted.

## Alternative manual deployment

If you do not want to use GitHub Actions, download the public source ZIP, extract it, open a terminal in `cloudflare/worker`, and run:

```sh
npm install
npx wrangler login
npx wrangler deploy
```

This authenticates directly with Cloudflare and still does not link a GitHub account.

## Security and service behavior

- Each operator deploys their own Worker to their own Cloudflare account. There is no shared account-wide token in RKRemote.
- The Worker forwards opaque encrypted binary frames. Noise XX and ChaCha20-Poly1305 protect application payloads end-to-end.
- Cloudflare can still observe connection metadata such as IP addresses, room IDs, timing, frame sizes and traffic volume.
- All application traffic is relayed through Cloudflare; bandwidth, Durable Objects and plan limits/pricing still apply.
- The deployment workflow has not been verified against a real Cloudflare account in this repository milestone. A successful GitHub workflow and a real two-device connection test are still required before calling the setup complete.
