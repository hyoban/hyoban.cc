import { cn } from '~/lib/utils'

export function Input({ className, ...props }: React.ComponentProps<'input'>) {
  return (
    <input
      className={cn(
        'flex h-10 w-full rounded-md border border-gray-7 bg-gray-2 px-3 py-2 text-sm ring-offset-gray-2 file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-gray-11 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gray-8 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  )
}
