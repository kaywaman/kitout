/** Home, then each folder below it: the breadcrumbs of the folder and file pickers. */
export function breadcrumbs(path: string, home: string): { name: string; path: string }[] {
  const crumbs = [{ name: "Home", path: home }];
  if (path !== home && !path.startsWith(home + "/")) {
    return crumbs;
  }
  let current = home;
  for (const part of path.slice(home.length).split("/").filter(Boolean)) {
    current = `${current}/${part}`;
    crumbs.push({ name: part, path: current });
  }
  return crumbs;
}
