import { cli, Strategy } from '@jackwener/opencli/registry';
import {
  ArgumentError,
  AuthRequiredError,
  CommandExecutionError,
} from '@jackwener/opencli/errors';

const COMPOSER_SELECTOR = '#editable-message-text';
const CAPTION_SELECTOR = '#editable-message-text-modal';
const MEDIA_INPUT_SELECTOR = 'input[data-opencli-telegram-media-input="true"]';
const MAX_MEDIA = 10;

function parseArguments(kwargs) {
  const text = String(kwargs.text ?? '');
  const channel = String(kwargs.channel ?? '').replace(/^@/, '').trim();
  const peer = String(kwargs.peer ?? '').trim();
  const media = String(kwargs.media ?? '')
    .split(',')
    .map((path) => path.trim())
    .filter(Boolean);
  const execute = kwargs.execute === true || String(kwargs.execute).toLowerCase() === 'true';

  if (!channel || !/^[A-Za-z0-9_]+$/.test(channel)) {
    throw new ArgumentError('Telegram publish requires a valid --channel username.');
  }
  if (!/^-\d+$/.test(peer)) {
    throw new ArgumentError('Telegram publish requires a negative numeric --peer id.');
  }
  if (!text.trim() && media.length === 0) {
    throw new ArgumentError('Telegram publish requires text or media.');
  }
  if (media.length > MAX_MEDIA) {
    throw new ArgumentError(`Telegram publish supports at most ${MAX_MEDIA} media attachments.`);
  }

  return { channel, execute, media, peer, text };
}

async function inspectChannel(page, peer) {
  return page.evaluate(`(async () => {
    const peer = ${JSON.stringify(peer)};
    const deadline = Date.now() + 15000;
    const visible = (element) => Boolean(element)
      && (element.offsetParent !== null || element.getClientRects().length > 0);

    while (Date.now() < deadline) {
      const composer = document.querySelector(${JSON.stringify(COMPOSER_SELECTOR)});
      const routeMatches = window.location.hash === '#' + peer;
      if (routeMatches && visible(composer)) {
        return {
          ok: true,
          draft: (composer.innerText || composer.textContent || '').trim(),
          title: document.title,
        };
      }

      const bodyText = document.body?.innerText || '';
      if (/log in to telegram|sign in to telegram|qr code/i.test(bodyText)) {
        return { ok: false, authRequired: true };
      }
      await new Promise((resolve) => setTimeout(resolve, 250));
    }

    return { ok: false, authRequired: false };
  })()`);
}

async function readNewestMessageId(page) {
  const result = await page.evaluate(`(() => {
    const ids = Array.from(document.querySelectorAll('#MiddleColumn .Message[data-message-id]'))
      .map((element) => Number(element.getAttribute('data-message-id')))
      .filter(Number.isSafeInteger);
    return ids.length > 0 ? Math.max(...ids) : 0;
  })()`);
  return Number(result) || 0;
}

async function captureMediaInput(page) {
  return page.evaluate(`(async () => {
    const marker = 'data-opencli-telegram-media-input';
    const visible = (element) => Boolean(element)
      && (element.offsetParent !== null || element.getClientRects().length > 0);
    for (const element of document.querySelectorAll('input[' + marker + ']')) {
      element.removeAttribute(marker);
    }

    const attachButton = document.querySelector('#attach-menu-button');
    if (!visible(attachButton)) {
      return { ok: false, message: 'Telegram attachment button was not found.' };
    }
    attachButton.click();

    let item;
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      item = Array.from(document.querySelectorAll('#attach-menu-controls [role="menuitem"]'))
        .find((element) => visible(element)
          && (element.querySelector('.icon-photo') || /photo or video/i.test(element.textContent || '')));
      if (item) break;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    if (!item) {
      return { ok: false, message: 'Telegram Photo or Video action was not found.' };
    }

    const originalClick = HTMLInputElement.prototype.click;
    let captured;
    HTMLInputElement.prototype.click = function openCliCapture() {
      if (this.type === 'file') {
        captured = this;
        this.setAttribute(marker, 'true');
        if (!this.isConnected) document.body.appendChild(this);
        return;
      }
      return originalClick.call(this);
    };

    try {
      item.click();
    } finally {
      HTMLInputElement.prototype.click = originalClick;
    }

    if (!captured) {
      return { ok: false, message: 'Telegram did not create its media file input.' };
    }
    return {
      ok: true,
      accept: captured.accept,
      multiple: captured.multiple,
    };
  })()`);
}

async function attachMedia(page, media) {
  if (!page.setFileInput) {
    throw new CommandExecutionError(
      'Telegram media publishing requires Browser Bridge file upload support.',
      'Update OpenCLI and reconnect the browser extension.',
    );
  }

  const input = await captureMediaInput(page);
  if (!input?.ok || !input.multiple) {
    throw new CommandExecutionError(input?.message ?? 'Telegram media input is not usable.');
  }

  await page.setFileInput(media, MEDIA_INPUT_SELECTOR);
  const dispatched = await page.evaluate(`(() => {
    const input = document.querySelector(${JSON.stringify(MEDIA_INPUT_SELECTOR)});
    if (!input || !input.files || input.files.length !== ${JSON.stringify(media.length)}) {
      return { ok: false, count: input?.files?.length ?? 0 };
    }
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return { ok: true, count: input.files.length };
  })()`);
  if (!dispatched?.ok) {
    throw new CommandExecutionError(
      `Telegram accepted ${dispatched?.count ?? 0} of ${media.length} media attachments.`,
    );
  }

  const modal = await page.evaluate(`(async () => {
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
      const input = document.querySelector(${JSON.stringify(CAPTION_SELECTOR)});
      if (input && (input.offsetParent !== null || input.getClientRects().length > 0)) {
        return { ok: true };
      }
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    return { ok: false };
  })()`);
  if (!modal?.ok) {
    throw new CommandExecutionError('Telegram media preview did not become ready.');
  }
}

async function focusEmptyEditor(page, selector) {
  return page.evaluate(`(() => {
    const input = document.querySelector(${JSON.stringify(selector)});
    if (!input) return { ok: false, message: 'Telegram text editor was not found.' };
    const draft = (input.innerText || input.textContent || '').trim();
    if (draft) return { ok: false, message: 'Telegram already contains an unsent draft.' };
    input.focus();
    return { ok: true };
  })()`);
}

async function verifyEditorText(page, selector, text) {
  return page.evaluate(`(async () => {
    const expected = ${JSON.stringify(text)}.replace(/\r\n/g, '\n').trim();
    const deadline = Date.now() + 10000;
    while (Date.now() < deadline) {
      const input = document.querySelector(${JSON.stringify(selector)});
      const actual = (input?.innerText || input?.textContent || '').replace(/\r\n/g, '\n').trim();
      if (actual === expected) return { ok: true, actual };
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
    const input = document.querySelector(${JSON.stringify(selector)});
    return {
      ok: false,
      actual: (input?.innerText || input?.textContent || '').replace(/\r\n/g, '\n').trim(),
    };
  })()`);
}

async function insertText(page, selector, text) {
  if (!text) return;

  const focused = await focusEmptyEditor(page, selector);
  if (!focused?.ok) {
    throw new CommandExecutionError(focused?.message ?? 'Telegram text editor is unavailable.');
  }

  let inserted = false;
  if (page.insertText) {
    try {
      await page.insertText(text);
      inserted = true;
    } catch {
      inserted = false;
    }
  }

  if (!inserted) {
    const fallback = await page.evaluate(`(() => {
      const input = document.querySelector(${JSON.stringify(selector)});
      if (!input) return { ok: false };
      input.focus();
      const text = ${JSON.stringify(text)};
      const ok = document.execCommand('insertText', false, text);
      if (!ok) {
        const transfer = new DataTransfer();
        transfer.setData('text/plain', text);
        input.dispatchEvent(new ClipboardEvent('paste', {
          bubbles: true,
          cancelable: true,
          clipboardData: transfer,
        }));
      }
      return { ok: true };
    })()`);
    if (!fallback?.ok) {
      throw new CommandExecutionError('Telegram text insertion failed.');
    }
  }

  const verified = await verifyEditorText(page, selector, text);
  if (!verified?.ok) {
    throw new CommandExecutionError(
      'Telegram text could not be verified before submission.',
      `Observed composer text: ${verified?.actual ?? ''}`,
    );
  }
}

async function clickSend(page, withMedia) {
  return page.evaluate(`(async () => {
    const withMedia = ${JSON.stringify(withMedia)};
    const visible = (element) => Boolean(element)
      && (element.offsetParent !== null || element.getClientRects().length > 0);
    const deadline = Date.now() + 30000;

    while (Date.now() < deadline) {
      let button;
      if (withMedia) {
        const input = document.querySelector(${JSON.stringify(CAPTION_SELECTOR)});
        const modal = input?.closest('.Modal');
        button = Array.from(modal?.querySelectorAll('button') || [])
          .find((candidate) => visible(candidate)
            && candidate.querySelector('.icon-new-send')
            && !candidate.disabled
            && candidate.getAttribute('aria-disabled') !== 'true');
      } else {
        button = Array.from(document.querySelectorAll('#MiddleColumn button.main-button.send'))
          .find((candidate) => visible(candidate)
            && !candidate.disabled
            && candidate.getAttribute('aria-disabled') !== 'true');
      }

      if (button) {
        button.click();
        return { ok: true };
      }
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    return { ok: false };
  })()`);
}

async function waitForPublishedMessage(page, beforeId, text) {
  return page.evaluate(`(async () => {
    const beforeId = ${JSON.stringify(beforeId)};
    const expected = ${JSON.stringify(text)}.replace(/\r\n/g, '\n').trim();
    const deadline = Date.now() + 45000;

    while (Date.now() < deadline) {
      const candidates = Array.from(document.querySelectorAll('#MiddleColumn .Message[data-message-id]'))
        .map((element) => ({
          element,
          id: Number(element.getAttribute('data-message-id')),
        }))
        .filter((item) => Number.isSafeInteger(item.id) && item.id > beforeId)
        .sort((left, right) => right.id - left.id);
      const match = expected
        ? candidates.find(({ element }) => {
            const actual = (element.innerText || element.textContent || '').replace(/\r\n/g, '\n').trim();
            return actual.includes(expected);
          })
        : candidates[0];
      if (match) return { ok: true, id: String(match.id) };
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    return { ok: false };
  })()`);
}

cli({
  site: 'telegram',
  name: 'publish',
  description: 'Publish text and media to a Telegram channel through the visible Web UI',
  access: 'write',
  example: 'opencli telegram publish "A moment" --channel hyoban_travel --peer -1003981320482 --execute -f json',
  domain: 'web.telegram.org',
  strategy: Strategy.UI,
  browser: true,
  args: [
    { name: 'text', type: 'string', positional: true, required: false, default: '', help: 'Message or media caption' },
    { name: 'media', type: 'string', required: false, help: 'Comma-separated absolute media paths, max 10' },
    { name: 'channel', type: 'string', required: true, help: 'Public channel username used for the result URL' },
    { name: 'peer', type: 'string', required: true, help: 'Negative numeric Telegram Web peer id' },
    { name: 'execute', type: 'boolean', default: false, help: 'Actually publish; default is a UI validation dry run' },
  ],
  columns: ['status', 'message', 'id', 'url'],
  func: async (page, kwargs) => {
    if (!page) {
      throw new CommandExecutionError('Browser session required for Telegram publish.');
    }

    const input = parseArguments(kwargs);
    await page.goto(`https://web.telegram.org/a/#${input.peer}`, {
      waitUntil: 'load',
      settleMs: 1500,
    });
    const channelState = await inspectChannel(page, input.peer);
    if (channelState?.authRequired) {
      throw new AuthRequiredError('web.telegram.org');
    }
    if (!channelState?.ok) {
      throw new CommandExecutionError(
        `Telegram channel ${input.peer} did not expose a broadcast composer.`,
        'Confirm the peer id and that the current account can post to the channel.',
      );
    }
    if (channelState.draft) {
      throw new CommandExecutionError(
        'Telegram already contains an unsent draft.',
        'Review or clear the draft manually before publishing.',
      );
    }

    if (!input.execute) {
      return [{
        status: 'ready',
        message: `Telegram channel ${channelState.title} is ready; no content was submitted.`,
        id: '',
        url: '',
      }];
    }

    const beforeId = await readNewestMessageId(page);
    if (input.media.length > 0) {
      await attachMedia(page, input.media);
      await insertText(page, CAPTION_SELECTOR, input.text);
    } else {
      await insertText(page, COMPOSER_SELECTOR, input.text);
    }

    const clicked = await clickSend(page, input.media.length > 0);
    if (!clicked?.ok) {
      throw new CommandExecutionError('Telegram send control did not become ready.');
    }

    const published = await waitForPublishedMessage(page, beforeId, input.text);
    if (!published?.ok) {
      return [{
        status: 'unknown',
        message: 'Telegram submission was clicked but the new message id could not be confirmed.',
        id: '',
        url: '',
      }];
    }

    return [{
      status: 'success',
      message: 'Telegram message published successfully.',
      id: published.id,
      url: `https://t.me/${input.channel}/${published.id}`,
    }];
  },
});
