export function buildSnapshotReadinessSummaryView({
  snapshot = null,
  snapshotManifestCheck = null,
  rollbackPreconditionsCheck = null,
  dryRunSnapshotObservation = null,
} = {}) {
  const show = Boolean(snapshot || snapshotManifestCheck || rollbackPreconditionsCheck);
  if (!show) {
    return {
      show: false,
      headerText: '',
      message: '',
      linkageText: '',
      stateText: '',
      manifestText: '',
      checkLines: [],
      latestManifestText: '',
      emptyText: '',
    };
  }

  const manifestPreview = dryRunSnapshotObservation?.manifestPreview || null;

  return {
    show: true,
    headerText: `Snapshot readiness: ${snapshot?.status || fallbackStatus(snapshotManifestCheck, rollbackPreconditionsCheck)}`,
    message: snapshot?.message || '',
    linkageText: observationLinkageText(snapshot),
    stateText: snapshot ? snapshotStateText(snapshot) : '',
    manifestText: snapshot?.relativeManifestPath ? snapshotManifestText(snapshot) : '',
    checkLines: [snapshotManifestCheck, rollbackPreconditionsCheck].filter(Boolean).map(readinessCheckText),
    latestManifestText: manifestPreview ? latestManifestText(manifestPreview) : '',
    emptyText: manifestPreview ? '' : 'Queue and refresh a Local Agent dry-run to provide snapshot manifest evidence.',
  };
}

function fallbackStatus(snapshotManifestCheck, rollbackPreconditionsCheck) {
  return snapshotManifestCheck?.passed && rollbackPreconditionsCheck?.passed ? 'observed' : 'blocked';
}

function snapshotStateText(snapshot) {
  let text = `snapshot created: ${String(snapshot.snapshotCreated)}`;
  if (snapshot.manifestCreated !== undefined) {
    text += ` / manifest created: ${String(snapshot.manifestCreated)}`;
  }
  if (snapshot.writesPlanned !== undefined) {
    text += ` / writes planned: ${String(snapshot.writesPlanned)}`;
  }
  if (snapshot.writesCompleted !== undefined) {
    text += ` / writes completed: ${String(snapshot.writesCompleted)}`;
  }
  return text;
}

function snapshotManifestText(snapshot) {
  let text = `manifest: ${snapshot.manifestId || '(snapshot)'} / ${snapshot.relativeManifestPath}`;
  if (snapshot.fileCount !== undefined) {
    text += ` / files ${snapshot.fileCount}`;
  }
  return text;
}

function latestManifestText(manifestPreview) {
  let text = `latest dry-run manifest: ${manifestPreview.id || '(preview)'}`;
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

function readinessCheckText(check) {
  return `${check.passed ? 'pass' : 'blocked'} / ${check.key}: ${check.message}`;
}

function observationLinkageText(value) {
  const linkage = value?.observationLinkage;
  if (!linkage?.status) {
    return '';
  }
  const parts = [`observation linkage: ${linkage.status}`];
  if (linkage.releaseAttemptLinked !== undefined) {
    parts.push(`release-attempt linked: ${String(linkage.releaseAttemptLinked)}`);
  }
  if (linkage.sourceOnlyFallback !== undefined) {
    parts.push(`source-only fallback: ${String(linkage.sourceOnlyFallback)}`);
  }
  if (linkage.releaseAttemptId) {
    parts.push(`attempt ${String(linkage.releaseAttemptId).slice(0, 8)}`);
  }
  if (linkage.sourceRequestId) {
    parts.push(`source ${String(linkage.sourceRequestId).slice(0, 8)}`);
  }
  return parts.join(' / ');
}
