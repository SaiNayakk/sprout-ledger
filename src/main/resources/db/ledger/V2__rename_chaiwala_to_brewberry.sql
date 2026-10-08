-- CHAIWALA is renamed BREWBERRY across Sprout, but not here. Journal entries are immutable (journal_is_immutable):
-- the books keep every entry exactly as it was posted, including the symbol named in its description, and a
-- correction is a new entry, never an edit. Renaming a stock changes no amount, so nothing is posted either.
--
-- (Released in 0.3.2 as an UPDATE of old descriptions, which the trigger rightly refused on real data. Postgres
-- rolled that back wherever it was tried, so no database has it applied; this no-op takes its place.)
SELECT 1;
