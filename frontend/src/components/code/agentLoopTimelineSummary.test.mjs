import assert from 'node:assert/strict';
import { buildAgentLoopTimelineHistoryView } from './agentLoopTimelineSummary.js';

const view = buildAgentLoopTimelineHistoryView([
  {
    id: 'loop-1',
    status: 'PREVIEW_ONLY',
    maxSteps: 6,
    timeoutSeconds: 120,
    mutationEnabled: false,
    timelinePersistenceEnabled: true,
    cancellationEnabled: false,
    instruction: 'fix parser bug',
    createdAt: '2026-06-30T12:00:00Z',
    events: [
      {
        sequenceNumber: 1,
        eventType: 'LOOP_PREVIEW_CREATED',
        requiresApproval: false,
        mayMutate: false,
        enabled: false,
        details: {},
      },
      {
        sequenceNumber: 3,
        eventType: 'APPROVAL_CHECKPOINT_PREVIEW',
        phase: 'REQUEST_APPROVAL',
        executionTarget: 'USER_LOCAL_AGENT',
        toolName: 'patch.apply',
        requiresApproval: true,
        mayMutate: false,
        enabled: true,
        details: {
          action: 'Require explicit user approval before any side-effectful tool can run.',
        },
      },
      {
        sequenceNumber: 8,
        eventType: 'TIMEOUT_POLICY_REGISTERED',
        requiresApproval: false,
        mayMutate: false,
        enabled: false,
        details: {
          status: 'REGISTERED',
          timeoutSeconds: 120,
        },
      },
      {
        sequenceNumber: 9,
        eventType: 'CANCELLATION_POLICY_REGISTERED',
        requiresApproval: false,
        mayMutate: false,
        enabled: false,
        details: {
          status: 'DISABLED',
          cancellationEnabled: false,
        },
      },
      {
        sequenceNumber: 10,
        eventType: 'FINAL_RESULT_POLICY_REGISTERED',
        requiresApproval: false,
        mayMutate: false,
        enabled: false,
        details: {
          status: 'PENDING_PREVIEW_ONLY',
          finalResultEnabled: false,
        },
      },
      {
        sequenceNumber: 11,
        eventType: 'STOP_OUTCOME_POLICY_REGISTERED',
        requiresApproval: false,
        mayMutate: false,
        enabled: false,
        details: {
          status: 'REGISTERED',
          stopKey: 'WEAK_EVIDENCE',
          outcome: 'ASK_FOR_CLARIFICATION',
        },
      },
      {
        sequenceNumber: 12,
        eventType: 'STOP_OUTCOME_POLICY_REGISTERED',
        requiresApproval: false,
        mayMutate: false,
        enabled: false,
        details: {
          status: 'REGISTERED',
          stopKey: 'AGENT_UNAVAILABLE',
          outcome: 'WAIT_FOR_LOCAL_AGENT',
        },
      },
      {
        sequenceNumber: 13,
        eventType: 'STOP_OUTCOME_POLICY_REGISTERED',
        requiresApproval: false,
        mayMutate: false,
        enabled: false,
        details: {
          status: 'REGISTERED',
          stopKey: 'TOOL_FAILED',
          outcome: 'REPORT_TOOL_FAILURE',
        },
      },
      {
        sequenceNumber: 14,
        eventType: 'STOP_OUTCOME_POLICY_REGISTERED',
        requiresApproval: false,
        mayMutate: false,
        enabled: false,
        details: {
          status: 'REGISTERED',
          stopKey: 'APPROVAL_DENIED',
          outcome: 'REPORT_APPROVAL_DENIED',
        },
      },
      {
        sequenceNumber: 15,
        eventType: 'LOCAL_AGENT_OBSERVATION_RESULT',
        phase: 'OBSERVE',
        executionTarget: 'USER_LOCAL_AGENT',
        toolName: 'patch.apply',
        requiresApproval: true,
        mayMutate: false,
        enabled: true,
        details: {
          status: 'SUCCEEDED',
          freshObservationOnly: true,
          dryRun: true,
          mutationApplied: false,
        },
      },
      {
        sequenceNumber: 16,
        eventType: 'LOCAL_AGENT_APPROVAL_DECISION',
        phase: 'REQUEST_APPROVAL',
        executionTarget: 'USER_LOCAL_AGENT',
        toolName: 'patch.apply',
        requiresApproval: true,
        mayMutate: false,
        enabled: true,
        details: {
          status: 'APPROVED_HELD',
          approvalState: 'APPROVED',
        },
      },
    ],
  },
]);

assert.equal(view.headerText, 'recent agent loop timelines: 1');
assert.equal(view.timelines[0].headerText, 'loop timeline: PREVIEW_ONLY / max steps 6 / timeout 120s');
assert.equal(view.timelines[0].stateText, 'timeline state: mutation false / persistence true / cancellation false');
assert.equal(view.timelines[0].instructionText, 'instruction: fix parser bug');
assert.deepEqual(view.timelines[0].eventLines, [
  '1 LOOP_PREVIEW_CREATED / approval false / may mutate false / enabled false',
  '3 APPROVAL_CHECKPOINT_PREVIEW / REQUEST_APPROVAL / USER_LOCAL_AGENT / patch.apply / approval true / may mutate false / enabled true / action: Require explicit user approval before any side-effectful tool can run.',
  '8 TIMEOUT_POLICY_REGISTERED / approval false / may mutate false / enabled false / status REGISTERED / timeout 120s',
  '9 CANCELLATION_POLICY_REGISTERED / approval false / may mutate false / enabled false / status DISABLED / cancellation false',
  '10 FINAL_RESULT_POLICY_REGISTERED / approval false / may mutate false / enabled false / status PENDING_PREVIEW_ONLY / final result false',
  '11 STOP_OUTCOME_POLICY_REGISTERED / approval false / may mutate false / enabled false / status REGISTERED / stop WEAK_EVIDENCE / outcome ASK_FOR_CLARIFICATION',
  '12 STOP_OUTCOME_POLICY_REGISTERED / approval false / may mutate false / enabled false / status REGISTERED / stop AGENT_UNAVAILABLE / outcome WAIT_FOR_LOCAL_AGENT',
  '13 STOP_OUTCOME_POLICY_REGISTERED / approval false / may mutate false / enabled false / status REGISTERED / stop TOOL_FAILED / outcome REPORT_TOOL_FAILURE',
  '14 STOP_OUTCOME_POLICY_REGISTERED / approval false / may mutate false / enabled false / status REGISTERED / stop APPROVAL_DENIED / outcome REPORT_APPROVAL_DENIED',
  '15 LOCAL_AGENT_OBSERVATION_RESULT / OBSERVE / USER_LOCAL_AGENT / patch.apply / approval true / may mutate false / enabled true / status SUCCEEDED / fresh observation true / dry-run true / mutation applied false',
  '16 LOCAL_AGENT_APPROVAL_DECISION / REQUEST_APPROVAL / USER_LOCAL_AGENT / patch.apply / approval true / may mutate false / enabled true / status APPROVED_HELD / approval state APPROVED',
]);
assert.equal(buildAgentLoopTimelineHistoryView([]), null);
assert.equal(buildAgentLoopTimelineHistoryView(null), null);

console.log('agentLoopTimelineSummary view tests passed');
