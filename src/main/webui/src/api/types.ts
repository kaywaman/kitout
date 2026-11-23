/**
 * Names for the API types. They come from `schema.d.ts`, which `npm run generate:api` generates
 * from `openapi.json`, which the backend's OpenApiContractTest keeps equal to the real API.
 * Change the Java DTOs, not these lines: a mismatch then breaks `tsc` instead of the running UI.
 */
import type { components } from "./schema";

/**
 * The backend always sends every field (Jackson writes nulls), but generated OpenAPI types mark
 * record fields as optional. Responses are therefore read as "all fields present"; fields that
 * can be empty are checked with `??` or truthiness in the views.
 */
type Present<T> = T extends (infer U)[]
  ? Present<U>[]
  : T extends object
    ? { [K in keyof T]-?: Present<Exclude<T[K], undefined>> }
    : T;

type Schemas = { [K in keyof components["schemas"]]: Present<components["schemas"][K]> };

export type ServiceView = Schemas["ServiceView"];
export type ServiceSettingsView = Schemas["ServiceSettingsView"];
export type GroupView = Schemas["GroupView"];
export type GroupStartView = Schemas["GroupStartView"];
export type CheckView = Schemas["CheckView"];
export type DoctorReportView = Schemas["DoctorReportView"];
export type ScanView = Schemas["ScanView"];
export type BuildRequestView = Schemas["BuildRequestView"];
export type BuildView = Schemas["BuildView"];
export type PortView = Schemas["PortView"];
export type CloneCandidateView = Schemas["CloneCandidateView"];
export type CloneJobView = Schemas["CloneJobView"];
export type CloneItemRequest = Schemas["CloneItemRequest"];
export type RemoteFindingView = Schemas["RemoteFindingView"];
export type CleanupOutcomeView = Schemas["CleanupOutcomeView"];
export type SettingsView = Schemas["SettingsView"];
export type CredentialStatusView = Schemas["CredentialStatusView"];
export type MessagesView = Schemas["MessagesView"];
export type RunModeResultView = Schemas["RunModeResultView"];
export type RemoveAllView = Schemas["RemoveAllView"];
export type FolderListingView = Schemas["FolderListingView"];
export type FolderEntryView = Schemas["FolderEntryView"];
export type FolderPreviewView = Schemas["FolderPreviewView"];
export type HiddenProjectView = Schemas["HiddenProjectView"];
export type KitoutInfoView = Schemas["KitoutInfoView"];
export type SyncStatusView = Schemas["SyncStatusView"];
export type PullOutcomeView = Schemas["PullOutcomeView"];
export type DockerOverviewView = Schemas["DockerOverviewView"];
export type ComposeEnvironmentView = Schemas["ComposeEnvironmentView"];
export type ComposeJobView = Schemas["ComposeJobView"];
export type ContainerView = Schemas["ContainerView"];
export type ComposeBrowseView = Schemas["ComposeBrowseView"];
export type ComposeCandidateView = Schemas["ComposeCandidateView"];
export type PickedComposeFileView = Schemas["PickedComposeFileView"];
export type ImageTagsView = Schemas["ImageTagsView"];
export type RegistryStatusView = Schemas["RegistryStatusView"];
export type EnvVarView = Schemas["EnvVarView"];
export type BuildBatchView = Schemas["BuildBatchView"];
export type ResetPreviewView = Schemas["ResetPreviewView"];
export type ResetRequest = Schemas["ResetRequest"];
export type ResetOutcomeView = Schemas["ResetOutcomeView"];
export type RequirementsView = Schemas["RequirementsView"];
export type RequirementView = Schemas["RequirementView"];
export type TeamStatusView = Schemas["TeamStatusView"];
export type TeamValueView = Schemas["TeamValueView"];
export type EnvImportView = Schemas["EnvImportView"];
export type SetupView = Schemas["SetupView"];
export type FileCheckView = Schemas["FileCheckView"];
export type SetupProjectView = Schemas["SetupProjectView"];
export type TeamSuggestionsView = Schemas["TeamSuggestionsView"];
export type TeamDraftRequest = Schemas["TeamDraftRequest"];
export type TeamSourceRequest = Schemas["TeamSourceRequest"];
export type TeamSourceView = Schemas["TeamSourceView"];
export type AddedServicesView = Schemas["AddedServicesView"];
export type CurrentBuildView = Schemas["CurrentBuildView"];
export type VolumeView = Schemas["VolumeView"];
export type AddedLibrariesView = Schemas["AddedLibrariesView"];

/** Values of SetupProjectView.kind for repositories that are not services. */
export type LibraryKind = "java-library" | "ui-library";

/** Values of ServiceView.state, as the backend's ServiceState enum. */
export type ServiceState = "STOPPED" | "STARTING" | "RUNNING" | "STOPPING" | "FAILED";
/** Values of ServiceView.health, as the backend's ServiceHealth enum. Separate from the state. */
export type ServiceHealth = "HEALTHY" | "UNHEALTHY" | "NO_HEALTH_CHECK" | "UNKNOWN";
/** Values of ServiceView.mode, as the backend's RunMode enum. */
export type RunMode = "IMAGE" | "JVM" | "NATIVE" | "SCRIPT" | "IDE";
export const RUN_MODES: { value: RunMode; label: string }[] = [
  { value: "IMAGE", label: "Registry image" },
  { value: "JVM", label: "Local JVM build" },
  { value: "NATIVE", label: "Local native build" },
  { value: "SCRIPT", label: "npm script" },
  { value: "IDE", label: "In my IDE" },
];

/** The modes a service's type offers (a Node project has no JVM build, an image-only service only its image). */
export function runModesOf(s: { runModes?: string[] }): { value: RunMode; label: string }[] {
  return s.runModes?.length ? RUN_MODES.filter((m) => s.runModes!.includes(m.value)) : RUN_MODES;
}
