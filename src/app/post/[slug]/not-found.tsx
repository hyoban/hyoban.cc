import { Container } from '@radix-ui/themes'
import Link from 'next/link'

export default function NotFound() {
  return (
    <Container mx="auto" p="5" className="prose dark:prose-invert break-words">
      <h2>The post you were looking for could not be found.</h2>
      <p>
        Go back to
        {' '}
        <Link href="/">home</Link>
      </p>
    </Container>
  )
}
