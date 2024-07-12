import './src/lib/env'

import type { NextConfig } from 'next'

const nextConfig: NextConfig = {
  // output: 'export',
  // images: { unoptimized: true },
  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: '**',
      },
    ],
  },
  eslint: {
    ignoreDuringBuilds: true,
  },
  typescript: {
    ignoreBuildErrors: true,
  },
}

export default nextConfig
