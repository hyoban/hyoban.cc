# Publishing

This context defines the canonical content and the language used to distribute it to external destinations.

## Language

**Moment**:
The canonical record of a piece of content. Website rendering and every external publication derive from the Moment.
_Avoid_: Post, source post, imported post

**Hidden Moment**:
A Moment intentionally excluded from both the website and every Publication. It must become visible before it can be published.
_Avoid_: Draft, platform-only Moment

**Publication**:
An intentional distribution of a Moment to Platforms. It targets every supported Platform by default; a subset is reserved for recovery or an explicit exception, and creating or editing a Moment does not constitute Publication.
_Avoid_: Sync, automatic publish

**Publication Preview**:
A non-mutating view of every targeted Platform Adaptation. Every Preview must validate successfully before a Publication may begin.
_Avoid_: Draft Publication, partial preview

**Platform**:
An external destination that can receive a Publication of a Moment.
_Avoid_: Source, channel

**X**:
The current Platform for new Publications under the X brand. Historical Twitter URLs remain Twitter Provenance rather than a separate Platform.
_Avoid_: Twitter, when referring to new Publications

**Provenance**:
The external location from which historical content was originally obtained. Provenance is descriptive history, never the canonical identity or publication state of a Moment.
_Avoid_: Source of truth, publication receipt

**Publication Receipt**:
The durable outcome of publishing one Moment to one Platform. A successful Publication Receipt identifies the external publication, prevents duplicates, and allows failed or missing Platforms to be retried independently.
_Avoid_: Provenance, source URL, sync status

**Platform Adaptation**:
A deterministic representation of a Moment that satisfies one Platform's constraints without silently removing text or media. If the Moment cannot be represented completely, Publication to that Platform fails.
_Avoid_: Truncation, best-effort publishing
