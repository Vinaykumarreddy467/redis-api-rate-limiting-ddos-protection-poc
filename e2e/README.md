# Policy-group E2E script

Drives the admin console with Playwright and saves screenshots to `../screenshots/` (ignored by git).

Start the stack with `start.bat`, then:

```bash
cd e2e
npm ci
npx playwright install chromium
RATELIMIT_ADMIN_USER=pocadmin RATELIMIT_ADMIN_PASSWORD=admin123 node playwright-policy-group-test.js
```

The script exits if either variable is unset; it has no built-in credentials.
