/**
 * The kitout look: a quiet, dense developer tool. Neutral surfaces, one indigo accent, status
 * colours that mean exactly one thing each, and monospace for everything a developer copies
 * (ids, ports, paths, images).
 *
 * Light and dark follow the system until the developer picks one in the top bar
 * (MUI keeps that choice in localStorage). Change colours here, not in views.
 */
import { createTheme, type PaletteColor, type Theme } from "@mui/material/styles";

/** Monospace stack: whatever the developer's system has, no web font to download. */
export const MONO = '"JetBrains Mono", "Fira Code", "Cascadia Code", "SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace';
const SANS = 'Inter, "Segoe UI", system-ui, -apple-system, Roboto, "Helvetica Neue", Arial, sans-serif';

/** The three surfaces of the frame: top bar, sidebar, content (background.default). */
interface Surfaces {
  /** The top bar: deep navy from the logo, in both schemes; text and controls on it are light. */
  navbar: string;
  /** The sidebar: a shade between the content and the top bar. */
  sidebar: string;
}

declare module "@mui/material/styles" {
  interface Palette {
    surface: Surfaces;
  }
  interface PaletteOptions {
    surface?: Surfaces;
  }
}

declare module "@mui/material/Chip" {
  interface ChipPropsVariantOverrides {
    /** Tinted background, coloured text: the status chips. Reads calmer than "filled" in a long list. */
    soft: true;
  }
}

const SOFT_COLORS = ["default", "primary", "success", "warning", "error", "info"] as const;

/** Height of the top bar; the spacer under it uses the same value (theme.mixins.toolbar). */
export const APP_BAR_HEIGHT = 84;

/** Sidebar widths: open with labels, and collapsed to its icons. */
export const SIDEBAR_WIDTH = 224;
export const SIDEBAR_COLLAPSED_WIDTH = 64;

/**
 * Styles for controls on the navy top bar: light text, translucent outlines. Spread into `sx`.
 * Kept here so the top bar has no colours of its own.
 */
export const ON_NAVBAR = {
  text: "#ffffff",
  muted: "rgba(255, 255, 255, 0.72)",
  outline: "rgba(255, 255, 255, 0.28)",
  outlineHover: "rgba(255, 255, 255, 0.55)",
  hover: "rgba(255, 255, 255, 0.08)",
  field: "rgba(255, 255, 255, 0.06)",
  chip: "rgba(255, 255, 255, 0.1)",
  /** The main action (Start group): light blue with navy text, and a bit deeper when hovered. */
  action: "#bfdbfe",
  actionHover: "#93c5fd",
  border: "rgba(255, 255, 255, 0.08)",
  shadow: "0 1px 0 rgba(0, 0, 0, 0.12), 0 4px 16px rgba(10, 16, 40, 0.18)",
} as const;

/** A colour from its channel variable at some opacity: follows light and dark (cssVariables is on). */
const tint = (color: "primary" | "text", alpha: number) =>
  color === "text" ? `rgba(var(--mui-palette-text-primaryChannel) / ${alpha})` : `rgba(var(--mui-palette-primary-mainChannel) / ${alpha})`;

/** Tint from the colour's channel variable, so it follows light and dark without a second rule set. */
function softChip(theme: Theme, color: (typeof SOFT_COLORS)[number]) {
  const palette = (theme.vars ?? theme).palette;
  if (color === "default") {
    return { backgroundColor: palette.action.selected, color: palette.text.secondary };
  }
  // cssVariables is on, so the palette has its channel variables (mainChannel); the type does not know that
  const c = palette[color] as PaletteColor & { mainChannel: string };
  return {
    backgroundColor: `rgba(${c.mainChannel} / 0.12)`,
    color: c.dark,
    ...theme.applyStyles("dark", { color: c.light, backgroundColor: `rgba(${c.mainChannel} / 0.18)` }),
  };
}

export const theme = createTheme({
  cssVariables: { colorSchemeSelector: "class" },
  colorSchemes: {
    light: {
      palette: {
        primary: { main: "#4f46e5" },
        success: { main: "#15803d" },
        warning: { main: "#b45309" },
        error: { main: "#dc2626" },
        info: { main: "#0369a1" },
        background: { default: "#f6f7f9", paper: "#ffffff" },
        text: { primary: "#111827", secondary: "#4b5563" },
        divider: "#e5e7eb",
        surface: { navbar: "#16204a", sidebar: "#eceff5" },
      },
    },
    dark: {
      palette: {
        primary: { main: "#818cf8" },
        success: { main: "#4ade80" },
        warning: { main: "#fbbf24" },
        error: { main: "#f87171" },
        info: { main: "#38bdf8" },
        background: { default: "#0d1117", paper: "#151b23" },
        text: { primary: "#e6edf3", secondary: "#9198a1" },
        divider: "#2a313c",
        // the bar the deepest navy, the sidebar lighter than it, the content between (background.default)
        surface: { navbar: "#0c1229", sidebar: "#1a2130" },
      },
    },
  },
  shape: { borderRadius: 8 },
  mixins: { toolbar: { minHeight: APP_BAR_HEIGHT } },
  typography: {
    fontFamily: SANS,
    fontSize: 13.5,
    h1: { fontSize: "1.5rem", fontWeight: 700, letterSpacing: "-0.018em", lineHeight: 1.25 },
    h2: { fontSize: "0.9375rem", fontWeight: 650, letterSpacing: "-0.005em" },
    h3: { fontSize: "0.875rem", fontWeight: 650 },
    body2: { lineHeight: 1.55 },
    overline: { fontSize: "0.6875rem", fontWeight: 650, letterSpacing: "0.08em", lineHeight: 2 },
    button: { textTransform: "none", fontWeight: 600 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        "code, kbd, samp": { fontFamily: MONO, fontSize: "0.92em" },
        body: {
          WebkitFontSmoothing: "antialiased",
          MozOsxFontSmoothing: "grayscale",
          // Inter's clearer forms of 1, l, I and a; ignored by other fonts
          fontFeatureSettings: '"cv02", "cv03", "cv04", "cv11"',
        },
        "::selection": { backgroundColor: tint("primary", 0.22) },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true, size: "small" },
      styleOverrides: {
        root: { borderRadius: 6, paddingInline: 12 },
      },
    },
    MuiIconButton: {
      defaultProps: { size: "small" },
    },
    MuiTextField: {
      defaultProps: { size: "small" },
    },
    MuiSelect: {
      defaultProps: { size: "small" },
    },
    MuiChip: {
      defaultProps: { size: "small" },
      styleOverrides: {
        root: { fontWeight: 600 },
      },
      variants: SOFT_COLORS.map((color) => ({
        props: { variant: "soft" as const, color },
        style: ({ theme }: { theme: Theme }) => softChip(theme, color),
      })),
    },
    MuiPaper: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        // outlined panels get a hairline shadow in light mode: depth without heaviness
        outlined: ({ theme }) => ({
          borderRadius: 10,
          boxShadow: "0 1px 2px rgba(16, 24, 40, 0.05)",
          ...theme.applyStyles("dark", { boxShadow: "none" }),
        }),
      },
    },
    MuiCard: {
      defaultProps: { variant: "outlined" },
    },
    MuiTableRow: {
      styleOverrides: {
        // the panel around a table already draws the bottom edge
        root: { "&:last-child > td": { borderBottom: 0 } },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: { paddingTop: 10, paddingBottom: 10 },
        head: ({ theme }) => ({
          fontSize: "0.6875rem",
          fontWeight: 650,
          letterSpacing: "0.06em",
          textTransform: "uppercase",
          color: (theme.vars ?? theme).palette.text.secondary,
          whiteSpace: "nowrap",
        }),
      },
    },
    MuiTooltip: {
      defaultProps: { arrow: true, enterDelay: 400 },
      styleOverrides: { tooltip: { fontSize: "0.75rem", fontWeight: 500 } },
    },
    MuiListItemButton: {
      styleOverrides: {
        // navigation: the current page tinted in the accent, the others quiet
        root: ({ theme }) => ({
          color: (theme.vars ?? theme).palette.text.secondary,
          "& .MuiListItemIcon-root": { color: "inherit", minWidth: 34 },
          "&:hover": { backgroundColor: tint("text", 0.05), color: (theme.vars ?? theme).palette.text.primary },
          "&.Mui-selected, &.Mui-selected:hover": { backgroundColor: tint("primary", 0.1), color: (theme.vars ?? theme).palette.primary.main },
        }),
      },
    },
    MuiAppBar: {
      defaultProps: { elevation: 0, color: "inherit" },
    },
    MuiDialog: {
      styleOverrides: { paper: { borderRadius: 12 } },
    },
    MuiDialogTitle: {
      styleOverrides: { root: { fontSize: "1.0625rem", fontWeight: 650 } },
    },
    MuiAlert: {
      styleOverrides: { root: { alignItems: "flex-start" } },
    },
  },
});
