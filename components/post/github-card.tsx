import { formatDistance } from 'date-fns'

import { AppLink } from '~/app/external-link'
import { env } from '~/env'

import { Button } from '../ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '../ui/card'

export interface GitHubRepo {
  id: number
  node_id: string
  name: string
  full_name: string
  private: boolean
  html_url: string
  description: string
  fork: boolean
  url: string
  forks_url: string
  keys_url: string
  collaborators_url: string
  teams_url: string
  hooks_url: string
  issue_events_url: string
  events_url: string
  assignees_url: string
  branches_url: string
  tags_url: string
  blobs_url: string
  git_tags_url: string
  git_refs_url: string
  trees_url: string
  statuses_url: string
  languages_url: string
  stargazers_url: string
  contributors_url: string
  subscribers_url: string
  subscription_url: string
  commits_url: string
  git_commits_url: string
  comments_url: string
  issue_comment_url: string
  contents_url: string
  compare_url: string
  merges_url: string
  archive_url: string
  downloads_url: string
  issues_url: string
  pulls_url: string
  milestones_url: string
  notifications_url: string
  labels_url: string
  releases_url: string
  deployments_url: string
  created_at: string
  updated_at: string
  pushed_at: string
  git_url: string
  ssh_url: string
  clone_url: string
  svn_url: string
  homepage?: string
  size: number
  stargazers_count: number
  watchers_count: number
  language: string
  has_issues: boolean
  has_projects: boolean
  has_downloads: boolean
  has_wiki: boolean
  has_pages: boolean
  has_discussions: boolean
  forks_count: number
  archived: boolean
  disabled: boolean
  open_issues_count: number
  allow_forking: boolean
  is_template: boolean
  web_commit_signoff_required: boolean
  visibility: string
  forks: number
  open_issues: number
  watchers: number
  default_branch: string
  network_count: number
  subscribers_count: number
}

export async function GitHubCard({ repo }: { repo: string }) {
  const res = await fetch(
    `https://api.github.com/repos/${repo}`,
    {
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      },
    },
  )
  const data = await res.json() as GitHubRepo
  return (
    <Card className="not-prose">
      <CardHeader>
        <CardTitle>{data.full_name}</CardTitle>
        <CardDescription>{data.description}</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="flex items-center gap-2">
          {data.homepage && (
            <AppLink href={data.homepage}>
              <Button size="sm">
                Live
              </Button>
            </AppLink>
          )}
          <AppLink href={data.html_url}>
            <Button variant="outline" size="sm">
              GitHub
            </Button>
          </AppLink>
        </div>
      </CardContent>

      <CardFooter>
        <p className="flex items-center gap-1">
          <span className="i-lucide-code text-xs" />
          {data.language}
          <span className="i-lucide-star text-xs ml-2" />
          {data.stargazers_count}
          <span className="ml-2">
            Updated
            {' '}
            {formatDistance(
              new Date(data.updated_at),
              new Date(),
              { addSuffix: true },
            )}
          </span>
        </p>
      </CardFooter>
    </Card>
  )
}
