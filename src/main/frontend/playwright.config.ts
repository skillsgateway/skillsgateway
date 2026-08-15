import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  // Playwright clears its outputDir on each run; keep it away from the junit
  // files that reqstool consumes (vitest writes there too).
  outputDir: "test-results/pw-artifacts",
  fullyParallel: false,
  retries: 0,
  reporter: [["list"], ["junit", { outputFile: "test-results/playwright-junit.xml" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
