import { useState } from 'react';

export function useBusyTasks() {
  const [busy, setBusy] = useState('');
  const [busyTasks, setBusyTasks] = useState(() => new Set());
  const [error, setError] = useState('');

  function startBusy(label) {
    setBusyTasks((current) => {
      const next = new Set(current);
      next.add(label);
      return next;
    });
    setBusy(label);
  }

  function finishBusy(label) {
    setBusyTasks((current) => {
      const next = new Set(current);
      next.delete(label);
      setBusy((active) => (active === label ? Array.from(next).at(-1) || '' : active));
      return next;
    });
  }

  async function run(label, task) {
    startBusy(label);
    setError('');
    try {
      const result = await task();
      return result === undefined ? true : result;
    } catch (err) {
      setError(err.message || '요청 처리 중 오류가 발생했습니다.');
      return false;
    } finally {
      finishBusy(label);
    }
  }

  const loading = (name) => busyTasks.has(name) || busy === name;
  const loadingPrefix = (prefix) => Array.from(busyTasks).some((name) => name.startsWith(prefix)) || busy.startsWith(prefix);

  return {
    busy,
    error,
    setError,
    run,
    startBusy,
    finishBusy,
    loading,
    loadingPrefix,
  };
}
