import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{js,ts,jsx,tsx,mdx}"],
  theme: {
    extend: {
      colors: {
        nexus: {
          50: "#eef8ff",
          500: "#1683d8",
          700: "#0d4f83",
          950: "#06233d",
        },
      },
    },
  },
  plugins: [],
};

export default config;
