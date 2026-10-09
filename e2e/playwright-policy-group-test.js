const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const SCREENSHOT_DIR = path.join(__dirname, '..', 'screenshots');
const BASE_URL = 'http://localhost:4200';
const ADMIN_USER = process.env.RATELIMIT_ADMIN_USER;
const ADMIN_PASS = process.env.RATELIMIT_ADMIN_PASSWORD;
if (!ADMIN_USER || !ADMIN_PASS) {
  console.error('Set RATELIMIT_ADMIN_USER and RATELIMIT_ADMIN_PASSWORD before running this test.');
  process.exit(1);
}

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function takeScreenshot(page, name) {
  const filename = path.join(SCREENSHOT_DIR, `${name}-${Date.now()}.png`);
  await page.screenshot({ path: filename, fullPage: true });
  console.log(`Screenshot saved: ${filename}`);
  return filename;
}

async function captureConsoleErrors(page) {
  const errors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') {
      errors.push(msg.text());
    }
  });
  page.on('pageerror', error => {
    errors.push(error.message);
  });
  return errors;
}

async function captureFailedRequests(page) {
  const failed = [];
  page.on('response', response => {
    if (response.status() >= 400) {
      failed.push(`${response.status()} ${response.url()}`);
    }
  });
  return failed;
}

async function login(page) {
  console.log('Navigating to login page...');
  await page.goto(`${BASE_URL}/admin/login`);
  await page.waitForLoadState('networkidle');
  await takeScreenshot(page, '01-login-page');

  console.log('Filling login form...');
  await page.fill('input[name="admin-user"]', ADMIN_USER);
  await page.fill('input[name="admin-password"]', ADMIN_PASS);
  await page.click('button[type="submit"]');
  await page.waitForLoadState('networkidle');
  await sleep(1000);
  await takeScreenshot(page, '02-after-login');
}

async function testPoliciesPage(page) {
  console.log('Testing policies page...');
  await page.goto(`${BASE_URL}/admin/policies`);
  await page.waitForLoadState('networkidle');
  await sleep(3000);
  await takeScreenshot(page, '03-policies-page');

  // Get all text content to debug
  const bodyText = await page.locator('body').innerText();
  console.log('Page text preview:', bodyText.substring(0, 500));

  // Check for group list - wait for the groups section to load
  await page.waitForSelector('text=Policy groups', { timeout: 15000 }).catch(() => {});
  await sleep(1000);
  await takeScreenshot(page, '03b-policies-page-loaded');

  // Check for group list
  const groupList = await page.locator('text=Policy Groups').isVisible().catch(() => false);
  console.log('Policy Groups section visible:', groupList);
  
  // Also check for the create button
  const createBtn = await page.locator('button:has-text("Create policy group")').isVisible().catch(() => false);
  console.log('Create policy group button visible:', createBtn);
  
  // Check for any error messages
  const errorMsg = await page.locator('.alert-bad, .alert.alert-bad').first().innerText().catch(() => '');
  if (errorMsg) console.log('Error message:', errorMsg);
  
  // Check for loading state
  const loadingMsg = await page.locator('text=Loading policy groups').isVisible().catch(() => false);
  console.log('Loading groups:', loadingMsg);
}

async function testGroupEditor(page) {
  console.log('Testing group editor...');
  await page.goto(`${BASE_URL}/admin/policies`);
  await page.waitForLoadState('networkidle');
  await sleep(1000);

  // Look for "Create policy group" button
  const createBtn = page.locator('button:has-text("Create policy group")').first();
  if (await createBtn.isVisible().catch(() => false)) {
    await createBtn.click();
    await page.waitForLoadState('networkidle');
    await sleep(500);
    await takeScreenshot(page, '04-group-editor-create');

    // Fill group form - the group editor component should be visible now
    // Wait for the group editor to load
    await page.waitForSelector('app-policy-group-editor', { timeout: 10000 }).catch(() => {});
    await sleep(500);

    // Fill group name
    const nameInput = page.locator('input[name="name"], input[id*="name"]').first();
    if (await nameInput.isVisible().catch(() => false)) {
      await nameInput.fill('Test Group E2E');
      await sleep(200);
    }

    // Add endpoint - look for "Add endpoint" button
    const addEndpointBtn = page.locator('button:has-text("Add endpoint"), button:has-text("Add Endpoint")').first();
    if (await addEndpointBtn.isVisible().catch(() => false)) {
      await addEndpointBtn.click();
      await sleep(300);

      // Fill endpoint details
      const methodSelect = page.locator('select[name*="method"], select[id*="method"]').first();
      if (await methodSelect.isVisible().catch(() => false)) {
        await methodSelect.selectOption('GET');
      }
      const pathInput = page.locator('input[name*="path"], input[id*="path"]').first();
      if (await pathInput.isVisible().catch(() => false)) {
        await pathInput.fill('/api/products');
      }
      const displayNameInput = page.locator('input[name*="displayName"], input[id*="displayName"]').first();
      if (await displayNameInput.isVisible().catch(() => false)) {
        await displayNameInput.fill('Products Read E2E');
      }
      await sleep(200);

      // Add scope rule
      const addRuleBtn = page.locator('button:has-text("Add Rule"), button:has-text("Add scope rule"), button:has-text("Add rule")').first();
      if (await addRuleBtn.isVisible().catch(() => false)) {
        await addRuleBtn.click();
        await sleep(300);

        const scopeSelect = page.locator('select[name*="scope"], select[id*="scope"]').first();
        if (await scopeSelect.isVisible().catch(() => false)) {
          await scopeSelect.selectOption('IP');
        }
        const algorithmSelect = page.locator('select[name*="algorithm"], select[id*="algorithm"]').first();
        if (await algorithmSelect.isVisible().catch(() => false)) {
          await algorithmSelect.selectOption('FIXED_WINDOW');
        }
        const limitInput = page.locator('input[name*="limit"], input[id*="limit"]').first();
        if (await limitInput.isVisible().catch(() => false)) {
          await limitInput.fill('50');
        }
        const windowInput = page.locator('input[name*="window"], input[id*="window"]').first();
        if (await windowInput.isVisible().catch(() => false)) {
          await windowInput.fill('60s');
        }
        await sleep(200);
      }
    }

    // Save
    const saveBtn = page.locator('button:has-text("Save"), button:has-text("Create")').first();
    if (await saveBtn.isVisible().catch(() => false)) {
      await saveBtn.click();
      await page.waitForLoadState('networkidle');
      await sleep(1000);
      await takeScreenshot(page, '05-group-created');
    }
  } else {
    console.log('Create Group button not found, checking existing groups...');
    await takeScreenshot(page, '04-no-create-btn');
  }
}

async function testRequestTester(page) {
  console.log('Testing request tester...');
  await page.goto(`${BASE_URL}/overview`);
  await page.waitForLoadState('networkidle');
  await sleep(1000);
  await takeScreenshot(page, '06-overview-page');

  // The request demo is on the overview page - look for the section
  await takeScreenshot(page, '07-request-tester-section');

  // Check for mode selector
  const modeSelect = page.locator('select[name="mode"]').first();
  if (await modeSelect.isVisible().catch(() => false)) {
    await modeSelect.selectOption('groups');
    await sleep(500);
    await takeScreenshot(page, '08-request-tester-groups-mode');

    // Select group
    const groupSelect = page.locator('select[name="group"]').first();
    if (await groupSelect.isVisible().catch(() => false)) {
      const options = await groupSelect.locator('option').allTextContents();
      console.log('Available groups:', options);
      if (options.length > 1) {
        await groupSelect.selectOption({ index: 1 });
        await sleep(500);
      }
    }

    // Select endpoint
    const endpointSelect = page.locator('select[name="endpoint"]').first();
    if (await endpointSelect.isVisible().catch(() => false)) {
      const options = await endpointSelect.locator('option').allTextContents();
      console.log('Available endpoints:', options);
      if (options.length > 0) {
        await endpointSelect.selectOption({ index: 0 });
        await sleep(500);
      }
    }

    // Set request count
    const countInput = page.locator('input[name="count"]').first();
    if (await countInput.isVisible().catch(() => false)) {
      await countInput.fill('3');
      await sleep(200);
    }

    // Start demo
    const startBtn = page.locator('button[type="submit"]:has-text("Start demo")').first();
    if (await startBtn.isVisible().catch(() => false)) {
      await startBtn.click();
      await sleep(5000); // Wait for requests to complete
      await takeScreenshot(page, '09-request-tester-results');
    }
  }
}

async function testLegacyMode(page) {
  console.log('Testing legacy mode...');
  await page.goto(`${BASE_URL}/overview`);
  await page.waitForLoadState('networkidle');
  await sleep(1000);

  const modeSelect = page.locator('select[name="mode"]').first();
  if (await modeSelect.isVisible().catch(() => false)) {
    await modeSelect.selectOption('legacy');
    await sleep(500);
    await takeScreenshot(page, '09-legacy-mode');
  }
}

async function main() {
  console.log('Starting Playwright test...');
  console.log(`Base URL: ${BASE_URL}`);
  console.log(`Admin user: ${ADMIN_USER}`);

  const browser = await chromium.launch({ headless: false, slowMo: 500 });
  const context = await browser.newContext({
    viewport: { width: 1280, height: 720 }
  });
  const page = await context.newPage();

  const consoleErrors = await captureConsoleErrors(page);
  const failedRequests = await captureFailedRequests(page);

  try {
    await login(page);
    await testPoliciesPage(page);
    await testGroupEditor(page);
    await testRequestTester(page);
    await testLegacyMode(page);

    console.log('\n=== TEST SUMMARY ===');
    console.log('Console errors:', consoleErrors.length);
    consoleErrors.forEach(e => console.log('  -', e));
    console.log('Failed requests:', failedRequests.length);
    failedRequests.forEach(r => console.log('  -', r));

  } catch (error) {
    console.error('Test error:', error);
    await takeScreenshot(page, 'error-state');
  } finally {
    await browser.close();
  }
}

main().catch(console.error);