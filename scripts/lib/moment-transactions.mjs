import { readdir, rename, rm, stat } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { basename, dirname, join, resolve } from 'node:path'

export async function recoverMomentTransactions(root, directoryPrefix) {
  if (typeof directoryPrefix !== 'string'
    || !directoryPrefix
    || /[\\/]/.test(directoryPrefix)) {
    throw new Error(`Unsupported moment transaction prefix: ${directoryPrefix}`)
  }

  const rootPath = root instanceof URL ? fileURLToPath(root) : resolve(root)
  const paths = await readdir(rootPath, { recursive: true }).catch((error) => {
    if (error.code === 'ENOENT') {
      return []
    }

    throw error
  })
  const pattern = new RegExp(
    `^\\.(${escapeRegExp(directoryPrefix)}[^/]+)\\.(tmp|backup)$`,
  )
  const transactions = new Map()

  for (const relativePath of paths) {
    const match = basename(relativePath).match(pattern)

    if (!match) {
      continue
    }

    const transactionPath = join(rootPath, relativePath)
    const file = await stat(transactionPath)

    if (!file.isDirectory()) {
      continue
    }

    const targetPath = join(dirname(transactionPath), match[1])
    const transaction = transactions.get(targetPath) ?? { targetPath }
    transaction[match[2] === 'tmp' ? 'temporaryPath' : 'backupPath'] = transactionPath
    transactions.set(targetPath, transaction)
  }

  const result = {
    removedBackups: 0,
    removedTemporaries: 0,
    restoredBackups: 0,
  }

  for (const transaction of [...transactions.values()]
    .sort((first, second) => first.targetPath.localeCompare(second.targetPath))) {
    const targetExists = await exists(transaction.targetPath)

    if (transaction.temporaryPath) {
      await rm(transaction.temporaryPath, { force: true, recursive: true })
      result.removedTemporaries += 1
    }

    if (!transaction.backupPath) {
      continue
    }

    if (targetExists) {
      await rm(transaction.backupPath, { force: true, recursive: true })
      result.removedBackups += 1
    } else {
      await rename(transaction.backupPath, transaction.targetPath)
      result.restoredBackups += 1
    }
  }

  return result
}

async function exists(path) {
  try {
    await stat(path)
    return true
  } catch (error) {
    if (error.code === 'ENOENT') {
      return false
    }

    throw error
  }
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
