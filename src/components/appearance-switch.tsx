'use client'

import { useDark } from 'jotai-dark/react'

export function AppearanceSwitch({ className = '' }: { className?: string }) {
  const { toggleDark } = useDark({
    disableTransition: true,
    disableTransitionExclude: ['.i-lucide-sun', '.i-lucide-moon'],
    mode: 'data-theme',
  })

  return (
    <button
      aria-label="Toggle dark mode"
      title="Toggle dark mode"
      type="button"
      onClick={toggleDark}
      className={`flex ${className}`}
    >
      <div
        role="img"
        aria-hidden="true"
        className="i-lucide-sun scale-100 dark:scale-0 transition-transform duration-500 rotate-0 dark:-rotate-90"
      />
      <div
        role="img"
        aria-hidden="true"
        className="i-lucide-moon absolute scale-0 dark:scale-100 transition-transform duration-500 rotate-90 dark:rotate-0"
      />
    </button>
  )
}
