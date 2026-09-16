import type { Decorator, Preview } from "@storybook/react-vite";
import { useEffect } from "react";
import "../src/index.css";

/**
 * `parameters: { theme: "dark" }` renders a story in the portal's dark theme.
 *
 * The class goes on the document element rather than on a wrapper, because dialogs and toasts
 * render into portals attached to the body and would otherwise keep the light palette.
 */
const preview: Preview = {
  decorators: [
    ((Story, context) => {
      const dark = context.parameters.theme === "dark";
      useEffect(() => {
        document.documentElement.classList.toggle("dark", dark);
        return () => document.documentElement.classList.remove("dark");
      }, [dark]);
      return <Story />;
    }) satisfies Decorator,
  ],
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
