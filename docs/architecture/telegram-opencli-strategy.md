# Telegram OpenCLI Publication Strategy

## Selected strategy

- Strategy: `UI_SELECTOR`
- Contract: `visible-ui`
- Target: Telegram Web A, channel `hyoban_travel`, peer `-1003981320482`

## Evidence

- `opencli doctor` reported a healthy daemon and connected browser extension.
- The authenticated Telegram Web page rendered a stable channel link at `/#-1003981320482` and a broadcast composer at `#editable-message-text`.
- The attachment control is exposed as `#attach-menu-button`. Its "Photo or Video" action creates a multiple file input accepting PNG, JPEG, GIF, MP4, and QuickTime media.
- Existing channel messages expose durable numeric identities through `.Message[data-message-id]`; the newest observed message was `162`.
- No JSON HTTP or SSR candidate was observed. The application communicates primarily over its authenticated WebSocket session, so replaying a private API would be less stable and less auditable than using the visible UI.

## Mutation and verification contract

- The command is a dry run unless `--execute` is present.
- Before any mutation, it verifies the requested channel, an empty composer, the attachment count, and the presence of text or media.
- It uses semantic IDs and visible controls for composition and submission.
- Success requires a newly rendered numeric message ID and produces `https://t.me/hyoban_travel/<id>`.
- If submission is clicked but the new ID cannot be confirmed, the command reports an unknown outcome. The caller records that outcome as terminal so an automatic retry cannot create a duplicate.
- Missing authentication, invalid arguments, and changed selectors use typed OpenCLI errors.
