---
name: adding-e2e-tests
description: Use when setting up end-to-end testing with Playwright for a web application
---

# Adding E2E Tests

## Overview

Set up Playwright end-to-end tests with proper structure, page objects, and CI integration.

## When to Use

- New web app needs E2E testing
- Adding browser-based tests to existing project
- Migrating from Cypress/Selenium to Playwright
- Need to test user flows end-to-end

## The Process

### Step 1: Install Playwright

```bash
npm init playwright@latest
# Or add to existing project:
npm install -D @playwright/test
npx playwright install
```

### Step 2: Configure

Create `playwright.config.ts`:

```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
  },
});
```

### Step 3: Create Page Objects

```typescript
// e2e/pages/login.page.ts
import { type Page, type Locator } from '@playwright/test';

export class LoginPage {
  readonly page: Page;
  readonly emailInput: Locator;
  readonly passwordInput: Locator;
  readonly submitButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.emailInput = page.getByRole('textbox', { name: 'Email' });
    this.passwordInput = page.getByRole('textbox', { name: 'Password' });
    this.submitButton = page.getByRole('button', { name: 'Sign in' });
  }

  async login(email: string, password: string) {
    await this.emailInput.fill(email);
    await this.passwordInput.fill(password);
    await this.submitButton.click();
  }
}
```

### Step 4: Write Tests

```typescript
// e2e/auth.spec.ts
import { test, expect } from '@playwright/test';
import { LoginPage } from './pages/login.page';

test.describe('Authentication', () => {
  test('successful login redirects to dashboard', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await page.goto('/login');
    await loginPage.login('user@example.com', 'password');
    await expect(page).toHaveURL('/dashboard');
  });

  test('invalid credentials show error', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await page.goto('/login');
    await loginPage.login('wrong@example.com', 'wrong');
    await expect(page.getByRole('alert')).toContainText('Invalid credentials');
  });
});
```

### Step 5: Add to CI

Add to `.github/workflows/ci.yml`:

```yaml
  e2e:
    runs-on: ubuntu-latest
    needs: ci
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: 'npm'
      - run: npm ci
      - run: npx playwright install --with-deps
      - run: npx playwright test
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: playwright-report
          path: playwright-report/
```

### Step 6: Add Scripts

Add to `package.json`:

```json
{
  "scripts": {
    "test:e2e": "playwright test",
    "test:e2e:ui": "playwright test --ui",
    "test:e2e:debug": "playwright test --debug"
  }
}
```

## Best Practices

- **Use role-based locators** — `getByRole`, `getByLabel`, `getByText` over CSS selectors
- **Page Object Pattern** — Encapsulate page interactions in reusable classes
- **Avoid test interdependence** — Each test should work in isolation
- **Use fixtures** — For authentication state, test data setup
- **Trace on failure** — Enable trace viewer for debugging CI failures

## Checklist

- [ ] Playwright installed with browsers
- [ ] `playwright.config.ts` configured
- [ ] `e2e/` directory created with test structure
- [ ] Page objects for main pages
- [ ] At least one smoke test for critical flow
- [ ] CI integration with artifact upload on failure
- [ ] npm scripts for local development
