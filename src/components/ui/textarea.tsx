import { cn } from '~/lib/utils'

export function Textarea({ className, ...props }: React.ComponentProps<'textarea'>) {
  return (
    <textarea
      className={cn(
        'flex min-h-[80px] w-full rounded-md border border-gray-7 bg-gray-2 px-3 py-2 text-sm ring-offset-gray-2 placeholder:text-gray-11 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gray-8 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  )
}
