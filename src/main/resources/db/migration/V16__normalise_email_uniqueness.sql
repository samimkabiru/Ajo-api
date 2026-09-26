-- Email uniqueness becomes case-insensitive: Ada@X.com and ada@x.com are the same address.
-- The application now trims and lowercases email before saving; this brings existing rows
-- into line and enforces it in the database.

-- 1. Refuse to run, rather than delete anything, if existing rows would collide.
DO $$
DECLARE
    duplicates TEXT;
BEGIN
    SELECT string_agg(normalised || ' (' || n || ' users)', ', ')
      INTO duplicates
      FROM (SELECT lower(trim(email)) AS normalised, count(*) AS n
              FROM users
             WHERE email IS NOT NULL
             GROUP BY lower(trim(email))
            HAVING count(*) > 1) d;

    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION 'V16: users share an email that differs only by case or whitespace: %. '
                        'Resolve these by hand, then re-run the migration.', duplicates;
    END IF;
END $$;

-- 2. Replace the case-sensitive index.
DROP INDEX uq_users_email;

UPDATE users
   SET email = lower(trim(email))
 WHERE email IS NOT NULL
   AND email <> lower(trim(email));

CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;
