import type { Preview } from "@storybook/react-vite";
import "../src/index.css";

const preview: Preview = {
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    // Axe violations fail the story test run (ADR 0003: machine-verifiable UI quality).
    a11y: {
      test: "error",
    },
  },
};

export default preview;
