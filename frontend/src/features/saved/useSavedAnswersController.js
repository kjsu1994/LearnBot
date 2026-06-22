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

  function resetState() {
    setSavedAnswers([]);
    setSelectedSavedAnswer(null);
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
    refreshSavedAnswers,
    loadSavedAnswer,
    updateSavedAnswerTitle,
    deleteSavedAnswer,
    resetState,
  };
}
