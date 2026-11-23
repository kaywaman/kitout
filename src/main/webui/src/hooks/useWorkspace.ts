import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client.ts";
import type { GroupView, ServiceView } from "../api/types.ts";
import { usePolling } from "./usePolling.ts";

const GROUP_KEY = "kitout.group";

function storedGroup(): string {
  try {
    return localStorage.getItem(GROUP_KEY) ?? "";
  } catch {
    return "";
  }
}

/**
 * The shared picture of the workspace: services (refreshed every 2 s), groups, and the group
 * the developer works with. The chosen group is remembered in this browser.
 */
export function useWorkspace() {
  const services = usePolling(api.services, 2000);
  const groups = usePolling(api.groups, 15000);
  const [groupName, setGroupName] = useState<string>(storedGroup);

  const selectGroup = useCallback((name: string) => {
    setGroupName(name);
    try {
      localStorage.setItem(GROUP_KEY, name);
    } catch {
      // not remembered; fine
    }
  }, []);

  // pick the first group automatically, and forget a group that no longer exists
  useEffect(() => {
    const list = groups.data;
    if (!list) {
      return;
    }
    if (groupName && !list.some((g) => g.name === groupName)) {
      selectGroup(list[0]?.name ?? "");
    } else if (!groupName && list.length > 0) {
      selectGroup(list[0]!.name);
    }
  }, [groups.data, groupName, selectGroup]);

  const group: GroupView | undefined = groups.data?.find((g) => g.name === groupName);
  const allServices: ServiceView[] = services.data ?? [];

  return {
    services: allServices,
    servicesLoaded: services.data !== undefined,
    servicesError: services.error,
    refreshServices: services.refresh,
    groups: groups.data ?? [],
    refreshGroups: groups.refresh,
    group,
    selectGroup,
  };
}

export type Workspace = ReturnType<typeof useWorkspace>;
