import { useState } from 'react';

export function useSavedAnswersController({
  activeSpaceId,
  request,
  run,
  savedSummary,
  clearSavedAnswerReferences = () => {},
}) {
  const [savedAnswers, setSavedAnswers] = useState([]);
  const [selectedSavedAnswer, setSelectedSavedAnswer] = useState(null);
  const [savedAnswerQuery, setSavedAnswerQuery] = useState('');
  const [savedAnswerType, setSavedAnswerType] = useState('');
  const [ragConversations, setRagConversations] = useState([]);
  const [selectedRagConversation, setSelectedRagConversation] = useState(null);
  const [ragConversationType, setRagConversationType] = useState('');

  async function refreshSavedAnswers() {
    if (!activeSpaceId) return;
    const params = new URLSearchParams();
    params.set('spaceId', activeSpaceId);
    params.set('limit', '80');
    if (savedAnswerType) params.set('type', savedAnswerType);
    if (savedAnswerQuery.trim()) params.set('query', savedAnswerQuery.trim());
    await run('saved-list', async () => {
      const data = await request(`/api/saved-answers?${params.toString()}`);
      setSavedAnswers(data || []);
      setSelectedSavedAnswer((current) => {
        if (!current) return null;
        return data?.some((item) => item.id === current.id) ? current : null;
      });
    });
  }

  async function loadSavedAnswer(savedAnswerId) {
    await run(`saved-detail-${savedAnswerId}`, async () => {
      const data = await request(`/api/saved-answers/${savedAnswerId}`);
      setSelectedSavedAnswer(data);
    });
  }

  async function updateSavedAnswerTitle(savedAnswerId, title) {
    await run(`saved-title-${savedAnswerId}`, async () => {
      const data = await request(`/api/saved-answers/${savedAnswerId}`, {
        method: 'PATCH',
        json: { title },
      });
      setSelectedSavedAnswer(data);
      setSavedAnswers((current) => current.map((item) => (
        item.id === data.id ? savedSummary(data) : item
      )));
    });
  }

  async function deleteSavedAnswer(savedAnswerId) {
    if (!window.confirm('Delete this saved answer?')) return;
    await run(`saved-delete-${savedAnswerId}`, async () => {
      await request(`/api/saved-answers/${savedAnswerId}`, { method: 'DELETE' });
      setSavedAnswers((current) => current.filter((item) => item.id !== savedAnswerId));
      setSelectedSavedAnswer((current) => (current?.id === savedAnswerId ? null : current));
      clearSavedAnswerReferences(savedAnswerId);
    });
  }

  async function refreshRagConversations() {
    if (!activeSpaceId) return;
    await run('conversation-list', async () => {
      const domains = ragConversationType ? [ragConversationType] : ['DOCUMENT', 'CODE'];
      const results = await Promise.all(domains.map((domain) => {
        const params = new URLSearchParams();
        params.set('spaceId', activeSpaceId);
        params.set('domain', domain);
        return request(`/api/rag/conversations?${params.toString()}`).catch(() => []);
      }));
      const merged = results.flat().sort((a, b) => new Date(b.updatedAt || b.createdAt || 0) - new Date(a.updatedAt || a.createdAt || 0));
      setRagConversations(merged);
      setSelectedRagConversation((current) => {
        if (!current) return null;
        return merged.some((item) => item.id === current.conversation?.id) ? current : null;
      });
    });
  }

  async function loadRagConversation(conversationId) {
    await run(`conversation-detail-${conversationId}`, async () => {
      const data = await request(`/api/rag/conversations/${conversationId}`);
      setSelectedRagConversation(data);
    });
  }

  async function deleteRagConversation(conversationId) {
    if (!window.confirm('이 대화 히스토리를 삭제할까요? 삭제 후 대화 목록에서 사라집니다.')) return;
    await run(`conversation-delete-${conversationId}`, async () => {
      await request(`/api/rag/conversations/${conversationId}`, { method: 'DELETE' });
      setRagConversations((current) => current.filter((item) => item.id !== conversationId));
      setSelectedRagConversation((current) => (current?.conversation?.id === conversationId ? null : current));
      await refreshRagConversations();
    });
  }

  function resetState() {
    setSavedAnswers([]);
    setSelectedSavedAnswer(null);
    setRagConversations([]);
    setSelectedRagConversation(null);
  }

  return {
    savedAnswers,
    setSavedAnswers,
    selectedSavedAnswer,
    setSelectedSavedAnswer,
    savedAnswerQuery,
    setSavedAnswerQuery,
    savedAnswerType,
    setSavedAnswerType,
    ragConversations,
    selectedRagConversation,
    ragConversationType,
    setRagConversationType,
    refreshSavedAnswers,
    loadSavedAnswer,
    updateSavedAnswerTitle,
    deleteSavedAnswer,
    refreshRagConversations,
    loadRagConversation,
    deleteRagConversation,
    resetState,
  };
}
