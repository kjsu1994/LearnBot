export function buildDryRunSnapshotObservationSummaryView(observation = null) {
  if (!observation) {
    return {
      show: false,
      observationText: '',
      manifestText: '',
      filesText: '',
    };
  }

  return {
    show: true,
    observationText: snapshotObservationText(observation),
    manifestText: observation.manifestPreview ? snapshotManifestText(observation.manifestPreview) : '',
    filesText: observation.files?.length ? `snapshot files: ${summarizeDryRunObservationFiles(observation.files)}` : '',
  };
}

function snapshotObservationText(observation) {
  let text = `snapshot would create: ${String(observation.wouldCreate)}`;
  if (observation.created !== undefined) {
    text += ` / created: ${String(observation.created)}`;
  }
  if (observation.scope) {
    text += ` / ${observation.scope}`;
  }
  if (observation.location) {
    text += ` / ${observation.location}`;
  }
  return text;
}

function snapshotManifestText(manifestPreview) {
  let text = `snapshot manifest: ${manifestPreview.id || '(preview)'}`;
  if (manifestPreview.relativeManifestPath) {
    text += ` / ${manifestPreview.relativeManifestPath}`;
  }
  if (manifestPreview.created !== undefined) {
    text += ` / manifest created: ${String(manifestPreview.created)}`;
  }
  if (manifestPreview.writesPlanned !== undefined) {
    text += ` / writes planned: ${String(manifestPreview.writesPlanned)}`;
  }
  if (manifestPreview.writesCompleted !== undefined) {
    text += ` / writes completed: ${String(manifestPreview.writesCompleted)}`;
  }
  return text;
}

function summarizeDryRunObservationFiles(files = []) {
  return files
    .map((file) => `${file.path || '(unknown)'}:${file.hashMatches ? 'hash-ok' : 'hash-check'}/${file.contextMatches ? 'context-ok' : 'context-blocked'}`)
    .join(', ');
}
