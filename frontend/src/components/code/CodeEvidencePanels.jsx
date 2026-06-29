import { useEffect, useState } from 'react';
import { ChevronDown, ChevronUp, Eye } from 'lucide-react';
import { evidencePreviewLimit } from '../../config/constants.js';
import { Badge } from '../ui/badge.jsx';
import { Button } from '../ui/button.jsx';
import { DataTable } from '../ui/data-table.jsx';

function CodeEvidenceList({ evidence = [], onOpenEvidence }) {
  const [expanded, setExpanded] = useState(false);
  const evidenceKey = evidence.map((item) => item.chunkId || item.filePath || item.citationNumber).join('|');
  useEffect(() => {
    setExpanded(false);
  }, [evidenceKey]);
  if (!evidence.length) return <p className="empty compact-empty">{'?쒖떆??肄붾뱶 洹쇨굅媛 ?놁뒿?덈떎.'}</p>;
  const groupedEvidence = groupCodeEvidence(evidence);
  const visibleEvidence = expanded ? groupedEvidence : groupedEvidence.slice(0, evidencePreviewLimit);
  const hiddenCount = Math.max(groupedEvidence.length - visibleEvidence.length, 0);
  return (
    <div className={expanded ? 'evidence-section evidence-section-expanded' : 'evidence-section'}>
      <div className="evidence-header">
        <strong>{'肄붾뱶 洹쇨굅'}</strong>
        <small>{visibleEvidence.length}/{groupedEvidence.length}{'媛??뚯씪 ?쒖떆'}</small>
      </div>
      <div className="evidence-list">
        {visibleEvidence.map((group) => {
          const item = group.primary;
          const canOpen = Boolean(item.repositoryId && item.fileId);
          const primaryRange = codeEvidenceRange(item);
          const groupRanges = codeEvidenceRanges(group.items);
          const openRanges = groupRanges.length ? groupRanges : primaryRange;
          const metaText = group.items.length > 1
            ? `${group.items.length}媛?洹쇨굅 쨌 ${group.locationSummary}`
            : codeEvidenceMetaText(item);
          return (
            <article className="evidence-card code-evidence" key={group.evidenceKey}>
              <div className="result-heading">
                <strong title={item.filePath}>[{group.citationNumbers.join(', ')}] {item.filePath}</strong>
                {canOpen && (
                  <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, openRanges)}>
                    <Eye size={14} />
                    {'?닿린'}
                  </button>
                )}
              </div>
              <small>{metaText}</small>
              <p>{item.preview}</p>
              {group.items.length > 1 && (
                <div className="code-evidence-locations">
                  {group.items.map((part) => {
                    const partRange = codeEvidenceRange(part);
                    return (
                      <button
                        className="ghost-button compact-action"
                        disabled={!part.repositoryId || !part.fileId}
                        key={`${part.citationNumber}-${part.chunkId || part.lineStart || part.metadata?.changeType || 'part'}`}
                        type="button"
                        onClick={() => onOpenEvidence?.(part.repositoryId, part.fileId, partRange)}
                      >
                        [{part.citationNumber}] {codeEvidenceMetaText(part)}
                      </button>
                    );
                  })}
                </div>
              )}
            </article>
          );
        })}
      </div>
      {groupedEvidence.length > evidencePreviewLimit && (
        <button className="ghost-button compact-action evidence-toggle" type="button" onClick={() => setExpanded((current) => !current)}>
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          {expanded ? '?듭떖 洹쇨굅留?蹂닿린' : `?꾩껜 洹쇨굅 ?뚯씪 ${groupedEvidence.length}媛?蹂닿린`}
          {!expanded && hiddenCount > 0 ? <span>+{hiddenCount}</span> : null}
        </button>
      )}
    </div>
  );
}

function groupCodeEvidence(evidence = []) {
  const grouped = new Map();
  evidence.forEach((item, index) => {
    const key = codeEvidenceGroupKey(item, index);
    const current = grouped.get(key);
    if (!current) {
      grouped.set(key, {
        evidenceKey: key,
        primary: item,
        items: [item],
        citationNumbers: [item.citationNumber],
        locationSummary: codeEvidenceLocationSummary([item]),
      });
      return;
    }
    current.items.push(item);
    if (!current.citationNumbers.includes(item.citationNumber)) {
      current.citationNumbers.push(item.citationNumber);
    }
    if (Number(item.score || 0) > Number(current.primary.score || 0)) {
      current.primary = item;
    }
    current.locationSummary = codeEvidenceLocationSummary(current.items);
  });
  return Array.from(grouped.values());
}

function codeEvidenceGroupKey(item = {}, index = 0) {
  if (item.repositoryId && item.fileId) return `${item.repositoryId}:${item.fileId}`;
  if (item.repositoryName || item.filePath) return `${item.repositoryName || ''}:${item.filePath || ''}`;
  return item.chunkId || `code-evidence-${index}`;
}

function codeEvidenceRange(item = {}) {
  return item.lineStart > 0
    ? { start: item.lineStart, end: item.lineEnd || item.lineStart }
    : null;
}

function codeEvidenceRanges(items = []) {
  const seen = new Set();
  return items
    .map(codeEvidenceRange)
    .filter(Boolean)
    .filter((range) => {
      const key = `${range.start}-${range.end}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((left, right) => left.start - right.start || left.end - right.end);
}

function codeEvidenceMetaText(item = {}) {
  const isCommitDiff = item.metadata?.kind === 'commit_diff';
  if (isCommitDiff) {
    return `${item.metadata?.changeType || item.chunkType} 쨌 +${item.metadata?.insertions ?? 0}/-${item.metadata?.deletions ?? 0}`;
  }
  const location = item.lineStart > 0 ? `${item.lineStart}-${item.lineEnd || item.lineStart}` : 'lines -';
  return `${location} 쨌 ${item.chunkType || 'code'}`;
}

function codeEvidenceLocationSummary(items = []) {
  const values = items
    .map((item) => item.lineStart > 0 ? `${item.lineStart}-${item.lineEnd || item.lineStart}` : item.metadata?.changeType || item.chunkType)
    .filter(Boolean);
  return values.slice(0, 4).join(', ') + (values.length > 4 ? ` +${values.length - 4}` : '');
}

function CodeSearchResults({ results = [], onOpenEvidence }) {
  const columns = [
    {
      accessorKey: 'filePath',
      header: '?뚯씪',
      cell: ({ row }) => (
        <div className="code-table-file">
          <strong>{row.original.filePath}</strong>
          <small>{row.original.repositoryName}</small>
        </div>
      ),
    },
    {
      accessorKey: 'lineStart',
      header: '?쇱씤',
      cell: ({ row }) => <Badge variant="outline">{row.original.lineStart}-{row.original.lineEnd}</Badge>,
    },
    {
      accessorKey: 'score',
      header: '?먯닔',
      cell: ({ row }) => Number(row.original.score || 0).toFixed(3),
    },
    {
      id: 'actions',
      header: '',
      cell: ({ row }) => (
        <Button
          size="sm"
          variant="outline"
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            onOpenEvidence?.(row.original.repositoryId, row.original.fileId, { start: row.original.lineStart, end: row.original.lineEnd });
          }}
        >
          <Eye size={14} />
          {'?닿린'}
        </Button>
      ),
    },
  ];
  return (
    <DataTable
      className="code-search-table"
      columns={columns}
      data={results}
      empty={'肄붾뱶 寃??寃곌낵媛 ?놁뒿?덈떎.'}
      onRowClick={(item) => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}
    />
  );
}

function CodeReferenceResults({ result, onOpenEvidence }) {
  return (
    <div className="reference-results">
      <ReferenceGroup title={'?뺤쓽'} items={result.definitions || []} onOpenEvidence={onOpenEvidence} />
      <ReferenceGroup title={'李몄“'} items={result.references || []} onOpenEvidence={onOpenEvidence} />
    </div>
  );
}

function ReferenceGroup({ title, items, onOpenEvidence }) {
  return (
    <div className="reference-group">
      <h3>{title}</h3>
      {items.map((item) => (
        <article className="evidence-card code-evidence" key={item.chunkId}>
          <div className="result-heading">
            <strong>{item.filePath}</strong>
            <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}>
              <Eye size={14} />
              {'?닿린'}
            </button>
          </div>
          <small>{item.lineStart}-{item.lineEnd} {'쨌'} {item.chunkType}</small>
          <p>{item.content}</p>
        </article>
      ))}
      {!items.length && <p className="empty compact-empty">{'寃곌낵 ?놁쓬'}</p>}
    </div>
  );
}

export { CodeEvidenceList, CodeReferenceResults, CodeSearchResults };
