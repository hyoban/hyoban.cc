export function remarkInstantViewImages() {
  return (tree) => {
    transformChildren(tree)
  }
}

function transformChildren(node) {
  if (!Array.isArray(node.children)) {
    return
  }

  for (let index = 0; index < node.children.length; index += 1) {
    const child = node.children[index]

    transformChildren(child)

    const replacement = splitImageParagraph(child)
    if (!replacement) {
      continue
    }

    node.children.splice(index, 1, ...replacement)
    index += replacement.length - 1
  }
}

function splitImageParagraph(node) {
  if (node.type !== 'paragraph' || !Array.isArray(node.children)) {
    return null
  }

  const parts = []
  let paragraphChildren = []

  for (const child of node.children) {
    const image = getStandaloneImage(child)

    if (image) {
      appendParagraph(parts, node, paragraphChildren)
      parts.push(createFigure(node, image))
      paragraphChildren = []
      continue
    }

    paragraphChildren.push(child)
  }

  appendParagraph(parts, node, paragraphChildren)

  return parts.some(part => part.data?.hName === 'figure') ? parts : null
}

function getStandaloneImage(node) {
  if (node.type === 'image') {
    return node
  }

  if (node.type !== 'link' || !Array.isArray(node.children)) {
    return null
  }

  const children = getMeaningfulChildren(node)
  if (children.length !== 1 || children[0].type !== 'image') {
    return null
  }

  return children[0]
}

function createFigure(sourceNode, image) {
  return {
    ...sourceNode,
    children: [image],
    data: {
      ...sourceNode.data,
      hName: 'figure',
    },
  }
}

function appendParagraph(parts, sourceNode, children) {
  if (getMeaningfulChildren({ children }).length === 0) {
    return
  }

  parts.push({
    ...sourceNode,
    children,
  })
}

function getMeaningfulChildren(node) {
  return node.children.filter(child => child.type !== 'text' || child.value.trim() !== '')
}
