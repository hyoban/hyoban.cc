const TELEGRAM_POST_URL = /^https:\/\/telegram\.me\/([A-Za-z0-9_]+\/\d+)\/?(?:[?#].*)?$/
const TELEGRAM_LEFT_ALIGN_STYLE = '<style>body.widget_frame_base { margin-left: 0 !important; margin-right: auto !important; }</style>'
const TELEGRAM_IFRAME_STYLE = 'display: block; overflow: hidden; background-color: transparent; border: none; min-width: 320px; width: 100%;'
const HTML_ATTRIBUTE_ESCAPES = {
  '&': '&amp;',
  '"': '&quot;',
  '<': '&lt;',
  '>': '&gt;',
}

export function remarkTelegramWidgets() {
  return async (tree) => {
    await replaceTelegramLinks(tree)
  }
}

async function replaceTelegramLinks(node) {
  if (!Array.isArray(node.children)) {
    return
  }

  for (let index = 0; index < node.children.length; index += 1) {
    const child = node.children[index]
    const postPath = getStandaloneTelegramPostPath(child)

    if (postPath) {
      node.children[index] = { type: 'html', value: await renderTelegramPost(postPath) }
      continue
    }

    await replaceTelegramLinks(child)
  }
}

function getStandaloneTelegramPostPath(node) {
  if (node.type !== 'paragraph' || !Array.isArray(node.children)) {
    return null
  }

  const children = node.children.filter(child => child.type !== 'text' || child.value.trim() !== '')

  if (children.length !== 1) {
    return null
  }

  const [child] = children

  if (child.type !== 'link' || typeof child.url !== 'string') {
    return null
  }

  return TELEGRAM_POST_URL.exec(child.url)?.[1] ?? null
}

async function renderTelegramPost(postPath) {
  const response = await fetch(`https://telegram.me/${postPath}?embed=1`, {
    headers: {
      'user-agent': 'Mozilla/5.0',
    },
  })

  if (!response.ok) {
    throw new Error(`Failed to render Telegram widget ${postPath}: ${response.status} ${response.statusText}`)
  }

  const documentHtml = await response.text()

  if (!/<body\b[^>]*>[\s\S]*?<\/body>/i.test(documentHtml)) {
    throw new Error(`Telegram widget ${postPath} did not return an HTML body.`)
  }

  const cleanHtml = documentHtml
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/(["'(])\/\//g, '$1https://')

  const srcdoc = (/<\/head>/i.test(cleanHtml)
    ? cleanHtml.replace(/(\s*)<\/head>/i, `$1  ${TELEGRAM_LEFT_ALIGN_STYLE}$1</head>`)
    : `${TELEGRAM_LEFT_ALIGN_STYLE}${cleanHtml}`)
    .trim()

  return `<iframe class="telegram-widget-rendered" data-telegram-post="${postPath}" title="Telegram post ${postPath}" srcdoc="${escapeHtmlAttribute(srcdoc)}" width="100%" frameborder="0" scrolling="no" loading="lazy" onload="this.style.height=this.contentDocument.documentElement.scrollHeight+'px'" style="${TELEGRAM_IFRAME_STYLE}"></iframe>`
}

function escapeHtmlAttribute(value) {
  return value.replace(/[&"<>]/g, char => HTML_ATTRIBUTE_ESCAPES[char])
}
