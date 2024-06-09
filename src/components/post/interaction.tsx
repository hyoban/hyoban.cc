import type { InteractionCount } from 'sakuin'

import { cn } from '~/lib/utils'

export function InteractionView({
  interaction,
  className,
}: {
  interaction: InteractionCount
  className?: string
}) {
  return (
    <section className={cn('flex gap-3', className)}>
      {interaction.views > 0 && (
        <span className="flex gap-1 items-center">
          <span className="i-lucide-bar-chart text-sm" />
          <span>{interaction.views}</span>
        </span>
      )}
      {interaction.likes > 0 && (
        <span className="flex gap-1 items-center">
          <span className="i-lucide-thumbs-up text-sm" />
          <span>{interaction.likes}</span>
        </span>
      )}
      {interaction.tips > 0 && (
        <span className="flex gap-1 items-center">
          <span className="i-lucide-circle-dollar-sign text-sm" />
          <span>{interaction.tips}</span>
        </span>
      )}
      {interaction.comments > 0 && (
        <span className="flex gap-1 items-center">
          <span className="i-lucide-message-circle text-sm" />
          <span>{interaction.comments}</span>
        </span>
      )}
    </section>
  )
}
