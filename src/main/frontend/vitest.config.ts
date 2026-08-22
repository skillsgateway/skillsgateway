import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { storybookTest } from '@storybook/addon-vitest/vitest-plugin';
import { playwright } from '@vitest/browser-playwright';
const dirname = path.dirname(fileURLToPath(import.meta.url));

// More info at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": new URL("./src", import.meta.url).pathname
    }
  },
  test: {
    reporters: ["default", "junit"],
    // Top-level (not in the junit reporter's options) so `pnpm test:stories`
    // can redirect its report with --outputFile.junit and never clobber the
    // unit report that the traceability gate consumes.
    outputFile: {
      junit: "test-results/vitest-junit.xml"
    },
    projects: [{
      extends: true,
      test: {
        name: 'unit',
        environment: "jsdom",
        setupFiles: ["./src/test/setup.ts"],
        include: ["src/**/*.test.{ts,tsx}"],
        css: false
      }
    }, {
      extends: true,
      plugins: [
      // The plugin will run tests for the stories defined in your Storybook config
      // See options at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon#storybooktest
      storybookTest({
        configDir: path.join(dirname, '.storybook')
      })],
      test: {
        name: 'storybook',
        browser: {
          enabled: true,
          headless: true,
          // Raised from the 30s default while this project still ran inside
          // `mvnw verify` on a starved CI runner (#103); kept because waiting
          // longer costs nothing when the browser starts promptly.
          connectTimeout: 120_000,
          provider: playwright({}),
          instances: [{
            browser: 'chromium'
          }]
        }
      }
    }]
  }
});