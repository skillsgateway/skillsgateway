-- Lifecycle event names moved under the subject they are about: snapshot.<x> became
-- marketplace.snapshot.<x>, so that marketplace.<x> — what happened to the marketplace itself —
-- has somewhere to live that does not collide with them.
--
-- A stored filter is an exact-match string. Without this rewrite a subscriber registered before the
-- rename would keep a filter naming events the gateway no longer sends, stop receiving anything,
-- and nothing would report that it had. The prefix is the whole substitution: no event name
-- contained 'snapshot.' other than as its leading segment, and neither '*' nor 'audit.export'
-- contains it at all, so both are left exactly as they are.

UPDATE webhook_subscribers
SET events = replace(events, 'snapshot.', 'marketplace.snapshot.')
WHERE events LIKE '%snapshot.%';
