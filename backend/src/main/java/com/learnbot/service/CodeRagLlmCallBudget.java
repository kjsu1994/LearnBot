package com.learnbot.service;

final class CodeRagLlmCallBudget {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private CodeRagLlmCallBudget() {
    }

    static Scope open(int maxCalls, int reservedGenerationCalls) {
        State previous = CURRENT.get();
        State state = new State(Math.max(1, maxCalls), Math.max(0, reservedGenerationCalls));
        CURRENT.set(state);
        return new Scope(previous, state);
    }

    static void acquirePlanning(String stage) {
        State state = CURRENT.get();
        if (state == null) return;
        if (state.used >= state.maxCalls - state.reservedGenerationCalls) {
            throw new BudgetExceededException("Code RAG planning LLM budget exhausted before " + safe(stage));
        }
        state.used++;
    }

    static void acquireGeneration(String stage) {
        State state = CURRENT.get();
        if (state == null) return;
        if (state.used >= state.maxCalls) {
            throw new BudgetExceededException("Code RAG LLM budget exhausted before " + safe(stage));
        }
        state.used++;
    }

    static boolean hasCapacity() {
        State state = CURRENT.get();
        return state == null || state.used < state.maxCalls;
    }

    static int used() {
        State state = CURRENT.get();
        return state == null ? 0 : state.used;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown stage" : value;
    }

    static final class Scope implements AutoCloseable {
        private final State previous;
        private final State current;

        private Scope(State previous, State current) {
            this.previous = previous;
            this.current = current;
        }

        int used() {
            return current.used;
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException(String message) {
            super(message);
        }
    }

    private static final class State {
        private final int maxCalls;
        private final int reservedGenerationCalls;
        private int used;

        private State(int maxCalls, int reservedGenerationCalls) {
            this.maxCalls = maxCalls;
            this.reservedGenerationCalls = Math.min(reservedGenerationCalls, maxCalls - 1);
        }
    }
}
