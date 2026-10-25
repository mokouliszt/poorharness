import type { Config } from "tailwindcss";

// Colors are RGB channel triplets so opacity modifiers (bg-ink/40) work.
// Hex values in CSS variables silently break those modifiers in Tailwind 3.
const channel = (name: string) => `rgb(var(--${name}) / <alpha-value>)`;

export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        void: channel("void"),
        surface: channel("surface"),
        raised: channel("raised"),
        line: channel("line"),
        mute: channel("mute"),
        ink: channel("ink"),
        paper: channel("paper"),
        danger: channel("danger"),
      },
      fontFamily: {
        sans: ['"IBM Plex Sans"', '"Noto Sans JP"', '"Noto Sans CJK JP"', "system-ui", "sans-serif"],
        mono: ['"IBM Plex Mono"', "ui-monospace", "monospace"],
      },
      fontSize: {
        // 15px reading size for agent output on a phone; 1.25 ratio above it.
        xs: ["12px", { lineHeight: "16px" }],
        sm: ["13px", { lineHeight: "19px" }],
        base: ["15px", { lineHeight: "24px" }],
        lg: ["18px", { lineHeight: "26px" }],
        xl: ["22px", { lineHeight: "30px" }],
        "2xl": ["28px", { lineHeight: "34px" }],
      },
      borderRadius: {
        slip: "18px",
        sheet: "22px",
      },
      keyframes: {
        sonar: {
          "0%": { transform: "scale(0.6)", opacity: "0.7" },
          "100%": { transform: "scale(1.9)", opacity: "0" },
        },
        rise: {
          "0%": { transform: "translateY(100%)" },
          "100%": { transform: "translateY(0)" },
        },
        fade: {
          "0%": { opacity: "0" },
          "100%": { opacity: "1" },
        },
      },
      animation: {
        sonar: "sonar 1.8s cubic-bezier(0.2, 0.6, 0.3, 1) infinite",
        rise: "rise 220ms cubic-bezier(0.2, 0.8, 0.2, 1)",
        fade: "fade 160ms ease-out",
      },
    },
  },
  plugins: [],
} satisfies Config;
