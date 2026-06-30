export function buildRollbackReadinessSummaryView({
  rollback = null,
} = {}) {
  if (!rollback) {
    return {
      show: false,
      headerText: '',
      message: '',
      linkageText: '',
      blockingText: '',
      fileCheckLines: [],
      overflowText: '',
    };
  }

  const fileChecks = rollback.fileChecks || [];
  const visibleFileChecks = fileChecks.slice(0, 5);

  return {
    show: true,
    headerText: `Rollback manifest readiness: ${rollback.status || 'UNKNOWN'}`,
    message: rollback.message || '',
    linkageText: observationLinkageText(rollback),
    blockingText: blockingText(rollback),
    fileCheckLines: visibleFileChecks.map(fileCheckText),
    overflowText: fileChecks.length > visibleFileChecks.length
      ? `${fileChecks.length - visibleFileChecks.length} more rollback file checks hidden`
      : '',
  };
}

function blockingText(rollback) {
  let text = `blocking release: ${String(rollback.blocking)}`;
  if (rollback.fileCount !== undefined) {
    text += ` / files ${rollback.fileCount}`;
  }
  if (rollback.requiresUserApproval !== undefined) {
    text += ` / user approval: ${String(rollback.requiresUserApproval)}`;
  }
  return text;
}

function fileCheckText(check) {
  let text = `${check.path || '(target)'} -> ${check.snapshotRelativePath || '(snapshot)'}`;
  if (check.targetPathSafe !== undefined) {
    text += ` / target safe: ${String(check.targetPathSafe)}`;
  }
  if (check.snapshotPathSafe !== undefined) {
    text += ` / snapshot safe: ${String(check.snapshotPathSafe)}`;
  }
  return text;
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
