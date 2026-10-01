-- Set when a group with financial history is removed: the group becomes read-only and
-- drops out of the default list, but every row stays. Nullable so un-archiving later
-- is just clearing it.
ALTER TABLE groups ADD COLUMN archived_at TIMESTAMPTZ;
