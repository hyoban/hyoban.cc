import { Container } from '@radix-ui/themes'

export default function NotFound() {
  return (
    <Container mx="auto" className="prose dark:prose-invert break-words">
      <h2>The page you were looking for could not be found.</h2>
    </Container>
  )
}
