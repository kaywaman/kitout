import { useEffect, useState, type ReactNode } from "react";
import Alert from "@mui/material/Alert";
import AppBar from "@mui/material/AppBar";
import Badge from "@mui/material/Badge";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Container from "@mui/material/Container";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import List from "@mui/material/List";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";
import Paper from "@mui/material/Paper";
import Toolbar from "@mui/material/Toolbar";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useColorScheme, useTheme } from "@mui/material/styles";
import CloudDownloadOutlined from "@mui/icons-material/CloudDownloadOutlined";
import ConstructionOutlined from "@mui/icons-material/ConstructionOutlined";
import DarkModeOutlined from "@mui/icons-material/DarkModeOutlined";
import DnsOutlined from "@mui/icons-material/DnsOutlined";
import HealthAndSafetyOutlined from "@mui/icons-material/HealthAndSafetyOutlined";
import LightModeOutlined from "@mui/icons-material/LightModeOutlined";
import MenuOpenRounded from "@mui/icons-material/MenuOpenRounded";
import MenuRounded from "@mui/icons-material/MenuRounded";
import ReceiptLongOutlined from "@mui/icons-material/ReceiptLongOutlined";
import SettingsBrightnessOutlined from "@mui/icons-material/SettingsBrightnessOutlined";
import SettingsOutlined from "@mui/icons-material/SettingsOutlined";
import ViewInArOutlined from "@mui/icons-material/ViewInArOutlined";
import ChecklistRounded from "@mui/icons-material/ChecklistRounded";
import { GroupBar } from "./views/GroupBar.tsx";
import { ServicesPage } from "./views/ServicesPage.tsx";
import { DoctorPage } from "./views/DoctorPage.tsx";
import { DockerPage } from "./views/DockerPage.tsx";
import { BuildsPage } from "./views/BuildsPage.tsx";
import { ClonePage } from "./views/ClonePage.tsx";
import { KitoutLogPage } from "./views/KitoutLogPage.tsx";
import { SettingsPage } from "./views/SettingsPage.tsx";
import { SetupPage } from "./views/SetupPage.tsx";
import { passedMessage, startupPassed } from "./lib/setup.ts";
import { useToasts } from "./components/Toasts.tsx";
import LinearProgress from "@mui/material/LinearProgress";
import { LogWindow } from "./views/LogWindow.tsx";
import { RequirementsNotice, RequirementsScreen } from "./views/RequirementsScreen.tsx";
import { api } from "./api/client.ts";
import { usePolling } from "./hooks/usePolling.ts";
import { OPEN_WHEN_BLOCKED } from "./lib/requirements.ts";
import { useWorkspace } from "./hooks/useWorkspace.ts";
import { PAGES, pageHref, parseRoute, type Page, type Route } from "./lib/route.ts";
import { currentToken } from "./lib/session.ts";
import { APP_BAR_HEIGHT, ON_NAVBAR, SIDEBAR_COLLAPSED_WIDTH, SIDEBAR_WIDTH } from "./theme.ts";

/** Where the browser remembers a collapsed sidebar; storage may be unavailable, then it is just not remembered. */
const COLLAPSED_KEY = "kitout.sidebar.collapsed";
const readCollapsed = () => {
  try {
    return window.localStorage.getItem(COLLAPSED_KEY) === "true";
  } catch {
    return false;
  }
};
const writeCollapsed = (value: boolean) => {
  try {
    window.localStorage.setItem(COLLAPSED_KEY, String(value));
  } catch {
    // private mode or blocked storage: the choice lasts for this page only
  }
};

const NAV: Record<Page, { title: string; icon: ReactNode }> = {
  setup: { title: "Setup", icon: <ChecklistRounded fontSize="small" /> },
  services: { title: "Services", icon: <DnsOutlined fontSize="small" /> },
  doctor: { title: "Doctor", icon: <HealthAndSafetyOutlined fontSize="small" /> },
  docker: { title: "Docker", icon: <ViewInArOutlined fontSize="small" /> },
  builds: { title: "Builds", icon: <ConstructionOutlined fontSize="small" /> },
  clone: { title: "Clone", icon: <CloudDownloadOutlined fontSize="small" /> },
  log: { title: "kitout log", icon: <ReceiptLongOutlined fontSize="small" /> },
  settings: { title: "Settings", icon: <SettingsOutlined fontSize="small" /> },
};

function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

export function App() {
  const route = useRoute();
  const [, setLost] = useState(0);
  useEffect(() => {
    const onLost = () => setLost((n) => n + 1);   // re-render: without a key the page asks for the link
    window.addEventListener("kitout-session-lost", onLost);
    return () => window.removeEventListener("kitout-session-lost", onLost);
  }, []);
  if (!currentToken()) {
    return <NoSession />;
  }
  if (route.kind === "service-log") {
    return <LogWindow kind="service" id={route.serviceId} />;
  }
  if (route.kind === "build-log") {
    return <LogWindow kind="build" id={route.buildId} />;
  }
  return <Shell page={route.page} />;
}

function Shell({ page }: { page: Page }) {
  const workspace = useWorkspace();
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("md"));
  const [navOpen, setNavOpen] = useState(false);
  const [collapsedChoice, setCollapsedChoice] = useState(readCollapsed);
  const collapsed = wide && collapsedChoice;   // on small screens the sidebar is a drawer that opens over the page
  const sidebarWidth = collapsed ? SIDEBAR_COLLAPSED_WIDTH : SIDEBAR_WIDTH;
  const toggleNav = () => {
    if (wide) {
      setCollapsedChoice((c) => {
        writeCollapsed(!c);
        return !c;
      });
    } else {
      setNavOpen(true);
    }
  };
  // checked on every action by the backend; here only to show the screen and the notice
  const requirements = usePolling(api.requirements, 15000);
  const [checking, setChecking] = useState(false);
  const report = requirements.data;
  const blocked = report?.blocked === true;
  const checkAgain = () => {
    setChecking(true);
    api.checkRequirements().then(() => requirements.refresh()).catch(() => requirements.refresh()).finally(() => setChecking(false));
  };
  const showScreen = blocked && !(OPEN_WHEN_BLOCKED as readonly string[]).includes(page);

  // on start: everything is checked in the background; all good -> a success message and the services,
  // otherwise Setup, which shows the status and what to do
  const toasts = useToasts();
  const setup = usePolling(api.setup, 0);
  const [checkedFirstVisit, setCheckedFirstVisit] = useState(false);
  useEffect(() => {
    if (checkedFirstVisit || !setup.data) {
      return;
    }
    setCheckedFirstVisit(true);
    if (startupPassed(setup.data)) {
      toasts.info(passedMessage(setup.data));
      if (page === "setup") {
        window.location.hash = pageHref("services");
      }
    } else {
      window.location.hash = pageHref("setup");
    }
  }, [setup.data]);
  const startupChecking = !checkedFirstVisit && !setup.data && !setup.error;
  const setupDone = setup.data?.complete === true;

  useEffect(() => {
    document.title = `${NAV[page].title} - kitout`;
    setNavOpen(false);
  }, [page]);

  const managed = workspace.services.filter((s) => s.mode !== "IDE");
  const running = managed.filter((s) => s.state === "RUNNING").length;
  const failed = workspace.services.filter((s) => s.state === "FAILED").length;

  /** What a page's entry says next to its name: a chip when open, a dot on the icon when collapsed. */
  const status = (p: Page): { label: string; color: "warning" | "error" | "success" | "default"; title?: string } | null => {
    if (p === "setup" && setup.data && !setupDone) {
      return { label: "to do", color: "warning" };
    }
    if (blocked && !(OPEN_WHEN_BLOCKED as readonly string[]).includes(p)) {
      return { label: "blocked", color: "error", title: "Waits until this machine meets the team's requirements" };
    }
    if (!blocked && p === "services" && managed.length > 0) {
      return {
        label: failed ? `${failed} failed` : `${running}/${managed.length}`,
        color: failed ? "error" : running ? "success" : "default",
        title: `${running} of ${managed.length} services kitout runs are running${failed ? `, ${failed} failed` : ""}`,
      };
    }
    return null;
  };

  const nav = (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <Toolbar />
      <List component="nav" aria-label="Sections" sx={{ px: collapsed ? 1 : 1.5, py: 2.5 }}>
        {PAGES.map((p) => {
          const st = status(p);
          const item = (
            <ListItemButton key={p} component="a" href={pageHref(p)} selected={p === page}
              aria-current={p === page ? "page" : undefined} aria-label={collapsed ? NAV[p].title : undefined}
              sx={{ borderRadius: 1.5, mb: 0.5, py: 0.9, px: collapsed ? 0 : 1.5, justifyContent: collapsed ? "center" : "flex-start" }}>
              <ListItemIcon sx={collapsed ? { minWidth: 0, justifyContent: "center" } : undefined}>
                <Badge variant="dot" color={st && st.color !== "default" ? st.color : "primary"} invisible={!collapsed || !st || st.color === "default"}
                  overlap="circular">
                  {NAV[p].icon}
                </Badge>
              </ListItemIcon>
              {!collapsed && (
                <ListItemText primary={NAV[p].title} slotProps={{ primary: { variant: "body2", sx: { fontWeight: p === page ? 650 : 500 } } }} />
              )}
              {!collapsed && st && (
                <Tooltip title={st.title ?? ""}>
                  <Chip variant="soft" color={st.color} label={st.label} sx={{ height: 20 }} />
                </Tooltip>
              )}
            </ListItemButton>
          );
          return collapsed
            ? <Tooltip key={p} placement="right" title={st ? `${NAV[p].title} · ${st.label}` : NAV[p].title}>{item}</Tooltip>
            : item;
        })}
      </List>
      <Box sx={{ flexGrow: 1 }} />
      {workspace.servicesError && !collapsed && (
        <Alert severity="error" sx={{ m: 1.5 }} role="alert">{workspace.servicesError.message}</Alert>
      )}
    </Box>
  );

  return (
    <Box sx={{ display: "flex", minHeight: "100vh", bgcolor: "background.default" }}>
      <AppBar position="fixed" sx={{ zIndex: (t) => t.zIndex.drawer + 1, bgcolor: "surface.navbar", color: ON_NAVBAR.text,
        borderBottom: 1, borderColor: ON_NAVBAR.border, boxShadow: ON_NAVBAR.shadow }}>
        {/* three columns: brand left, the group and its actions centred, theme right */}
        <Toolbar sx={{
          minHeight: APP_BAR_HEIGHT, py: 2.5, px: { xs: 2, md: 3.5 }, columnGap: 2.5,
          display: "grid", gridTemplateColumns: { xs: "auto minmax(0, 1fr) auto", md: "1fr auto 1fr" }, alignItems: "center",
        }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, minWidth: 0 }}>
            <Tooltip title={wide ? (collapsed ? "Show the sidebar" : "Collapse the sidebar") : "Open navigation"}>
              <IconButton edge="start" onClick={toggleNav} sx={{ color: ON_NAVBAR.muted, "&:hover": { color: ON_NAVBAR.text, bgcolor: ON_NAVBAR.hover } }}
                aria-label={wide ? (collapsed ? "Show the sidebar" : "Collapse the sidebar") : "Open navigation"}
                aria-expanded={wide ? !collapsed : navOpen} aria-controls="kitout-sidebar">
                {wide && !collapsed ? <MenuOpenRounded /> : <MenuRounded />}
              </IconButton>
            </Tooltip>
            <Brand />
          </Box>
          <Box sx={{ display: "flex", justifyContent: "center", minWidth: 0 }}>
            <GroupBar workspace={workspace} />
          </Box>
          <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
            <ColorSchemeToggle />
          </Box>
        </Toolbar>
      </AppBar>

      <Drawer
        id="kitout-sidebar"
        variant={wide ? "permanent" : "temporary"}
        open={wide || navOpen}
        onClose={() => setNavOpen(false)}
        sx={(t) => ({
          width: wide ? sidebarWidth : SIDEBAR_WIDTH, flexShrink: 0,
          transition: t.transitions.create("width", { duration: t.transitions.duration.shorter }),
          "& .MuiDrawer-paper": {
            width: wide ? sidebarWidth : SIDEBAR_WIDTH, boxSizing: "border-box", overflowX: "hidden",
            bgcolor: "surface.sidebar", borderRight: 1, borderColor: "divider",
            transition: t.transitions.create("width", { duration: t.transitions.duration.shorter }),
          },
        })}
      >
        {nav}
      </Drawer>

      <Box component="main" id="main" sx={{ flexGrow: 1, minWidth: 0 }}>
        <Toolbar />
        <Container maxWidth="xl" sx={{ pt: { xs: 2.5, md: 3.5 }, pb: 4, px: { xs: 2, md: 3.5 } }}>
          {startupChecking && (
            <Box role="status" sx={{ mb: 2 }}>
              <Typography variant="body2" sx={{ color: "text.secondary", mb: 0.5 }}>Checking the team file, this machine and the projects…</Typography>
              <LinearProgress />
            </Box>
          )}
          {report && blocked && !showScreen && page !== "setup" && (
            <Alert severity="error" sx={{ mb: 2 }}>
              This machine does not meet the team's requirements: kitout builds and starts nothing until they are fixed.{" "}
              <Box component="a" href={pageHref("services")} sx={{ color: "inherit", fontWeight: 600 }}>See what to fix</Box>
            </Alert>
          )}
          {report && !blocked && page !== "setup" && <RequirementsNotice report={report} />}
          {showScreen && report ? <RequirementsScreen report={report} checking={checking} onCheck={checkAgain} /> : (
            <>
              {page === "setup" && <SetupPage workspace={workspace} onChanged={() => { void requirements.refresh(); void setup.refresh(); }} />}
              {page === "services" && <ServicesPage workspace={workspace} />}
              {page === "doctor" && <DoctorPage workspace={workspace} />}
              {page === "docker" && <DockerPage />}
              {page === "builds" && <BuildsPage workspace={workspace} />}
              {page === "clone" && <ClonePage workspace={workspace} />}
              {page === "log" && <KitoutLogPage />}
              {page === "settings" && <SettingsPage workspace={workspace} onTeamChanged={() => { void requirements.refresh(); void setup.refresh(); }} />}
            </>
          )}
        </Container>
      </Box>
    </Box>
  );
}

/**
 * The kitout mark (also the favicon, public/kitout-icon.svg): a K whose stem is three stacked services
 * and whose arms fan out the environment, with the one click that sets it all up as the amber dot.
 */
function Brand() {
  return (
    <Box component="a" href={pageHref("services")} aria-label="kitout home"
      sx={{ display: "flex", alignItems: "center", gap: 1.25, color: ON_NAVBAR.text, textDecoration: "none", flexShrink: 0 }}>
      {/* a hairline ring, so the icon's own navy tile stands out from the navy bar */}
      <Box component="img" src="/kitout-icon.svg" alt="" aria-hidden="true"
        sx={{ width: 32, height: 32, display: "block", borderRadius: "23%", boxShadow: `0 0 0 1px ${ON_NAVBAR.outline}` }} />
      <Typography sx={{ fontWeight: 750, fontSize: "1.125rem", letterSpacing: "-0.02em", display: { xs: "none", sm: "block" } }}>kitout</Typography>
    </Box>
  );
}

/** System, light, dark: one button cycles through them. MUI remembers the choice in this browser. */
function ColorSchemeToggle() {
  const { mode, setMode } = useColorScheme();
  if (!mode) {
    return null;   // not known before the first render on the client
  }
  const next = { system: "light", light: "dark", dark: "system" } as const;
  const label = { system: "Theme: follows the system", light: "Theme: light", dark: "Theme: dark" }[mode];
  const icon = { system: <SettingsBrightnessOutlined />, light: <LightModeOutlined />, dark: <DarkModeOutlined /> }[mode];
  return (
    <Tooltip title={`${label}. Click to change.`}>
      <IconButton aria-label={label} onClick={() => setMode(next[mode])}
        sx={{ color: ON_NAVBAR.muted, "&:hover": { color: ON_NAVBAR.text, bgcolor: ON_NAVBAR.hover } }}>{icon}</IconButton>
    </Tooltip>
  );
}

function NoSession() {
  return (
    <Box sx={{ minHeight: "100vh", display: "grid", placeItems: "center", p: 2, bgcolor: "background.default" }}>
      <Paper variant="outlined" sx={{ p: 4, maxWidth: 560 }}>
        <Typography variant="h1" gutterBottom>Open kitout with its link</Typography>
        <Typography sx={{ mb: 1.5 }}>
          For your safety, kitout only answers a browser that got its key. Run <code>kitout serve</code> in a terminal and
          open the link it prints, once: this browser remembers the key, also when kitout restarts.
        </Typography>
        <Typography sx={{ color: "text.secondary" }}>Already running? The link is in the terminal where kitout was started. After a reset of kitout's settings the key is new, so the link is needed once more.</Typography>
      </Paper>
    </Box>
  );
}
