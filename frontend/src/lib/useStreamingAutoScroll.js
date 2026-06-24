import { useEffect, useRef } from 'react';

function isScrollableElement(element) {
  if (!element || element === document.body || element === document.documentElement) {
    return false;
  }
  const style = window.getComputedStyle(element);
  const overflowY = style.overflowY;
  return /(auto|scroll|overlay)/.test(overflowY);
}

function findScrollContainer(anchor) {
  let current = anchor?.parentElement || null;
  while (current) {
    if (isScrollableElement(current)) return current;
    current = current.parentElement;
  }
  return window;
}

function getScrollTop(container) {
  return container === window ? window.scrollY : container.scrollTop;
}

function isNearBottom(container) {
  if (container === window) {
    return window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 40;
  }
  return container.scrollHeight - container.scrollTop - container.clientHeight <= 40;
}

function scrollToBottom(container) {
  if (container === window) {
    window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'auto' });
    return;
  }
  container.scrollTop = container.scrollHeight;
}

function useStreamingAutoScroll(anchorRef, streaming, content) {
  const stickyRef = useRef(true);
  const userPausedRef = useRef(false);
  const wasStreamingRef = useRef(false);
  const autoScrollUntilRef = useRef(0);
  const lastScrollYRef = useRef(0);
  const scrollContainerRef = useRef(null);

  useEffect(() => {
    if (!streaming) {
      wasStreamingRef.current = false;
      stickyRef.current = true;
      userPausedRef.current = false;
      scrollContainerRef.current = null;
      return undefined;
    }
    const scrollContainer = findScrollContainer(anchorRef.current);
    scrollContainerRef.current = scrollContainer;
    if (!wasStreamingRef.current) {
      wasStreamingRef.current = true;
      stickyRef.current = true;
      userPausedRef.current = false;
      lastScrollYRef.current = getScrollTop(scrollContainer);
    }

    function handleScroll() {
      if (Date.now() < autoScrollUntilRef.current) return;
      const nextScrollY = getScrollTop(scrollContainer);
      const movingDown = nextScrollY > lastScrollYRef.current;
      lastScrollYRef.current = nextScrollY;
      const nearBottom = isNearBottom(scrollContainer);
      if (userPausedRef.current) {
        if (movingDown && nearBottom) {
          userPausedRef.current = false;
          stickyRef.current = true;
        }
        return;
      }
      stickyRef.current = nearBottom;
    }

    function pauseAutoFollow() {
      userPausedRef.current = true;
      stickyRef.current = false;
    }

    function handleWheel(event) {
      if (Date.now() < autoScrollUntilRef.current) return;
      if (event.deltaY > 0 && isNearBottom(scrollContainer)) {
        userPausedRef.current = false;
        stickyRef.current = true;
        return;
      }
      if (event.deltaY < 0) {
        pauseAutoFollow();
      }
    }

    function handleTouchStart() {
      pauseAutoFollow();
    }

    function handleKeyDown(event) {
      const pauseKeys = new Set(['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', 'Space']);
      if (pauseKeys.has(event.key)) {
        pauseAutoFollow();
      }
    }

    const scrollTarget = scrollContainer === window ? window : scrollContainer;
    scrollTarget.addEventListener('scroll', handleScroll, { passive: true });
    scrollTarget.addEventListener('wheel', handleWheel, { passive: true });
    scrollTarget.addEventListener('touchstart', handleTouchStart, { passive: true });
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      scrollTarget.removeEventListener('scroll', handleScroll);
      scrollTarget.removeEventListener('wheel', handleWheel);
      scrollTarget.removeEventListener('touchstart', handleTouchStart);
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [anchorRef, streaming]);

  useEffect(() => {
    if (!streaming || !stickyRef.current || !anchorRef.current) return;
    const detectedContainer = findScrollContainer(anchorRef.current);
    const scrollContainer = scrollContainerRef.current === window && detectedContainer !== window
      ? detectedContainer
      : scrollContainerRef.current || detectedContainer;
    scrollContainerRef.current = scrollContainer;
    autoScrollUntilRef.current = Date.now() + 120;
    scrollToBottom(scrollContainer);
    requestAnimationFrame(() => {
      if (stickyRef.current) scrollToBottom(scrollContainer);
    });
  }, [anchorRef, streaming, content]);
}

export { useStreamingAutoScroll };
