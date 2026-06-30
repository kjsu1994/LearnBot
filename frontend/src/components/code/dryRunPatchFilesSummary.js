export function buildDryRunPatchFilesSummaryView(files = []) {
  const fileRows = files.map((file) => `${file.path}:${file.contextMatches ? 'context-ok' : 'context-blocked'}`);

  return {
    show: fileRows.length > 0,
    text: fileRows.length ? `files: ${fileRows.join(', ')}` : '',
  };
}
