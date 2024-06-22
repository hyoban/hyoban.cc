import Link from 'next/link'

export default function NotFound() {
  return (
    <main className="mx-auto max-w-[692px] p-6 sm:py-16 antialiased prose dark:prose-invert break-words">
      <h2>The post you were looking for could not be found.</h2>
      <p>
        Go back to
        {' '}
        <Link href="/">home</Link>
      </p>
    </main>
  )
}
