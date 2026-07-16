-- Performance composite indexes (perf audit 2026-07-15; see docs/perf/PERFORMANCE-AUDIT.md).
-- These back the hottest scheduler + public-listing predicates that previously had only
-- single-column indexes. Postgres cannot combine two independent single-column indexes to
-- serve "WHERE <filter> ORDER BY <sort>" efficiently, so it filtered then sorted; each
-- composite below turns that into a single index range scan.
--
-- CREATE INDEX (non-concurrent) briefly write-locks each table while the index builds. The
-- tables are modest today so that pause is negligible; if any of these grows large, rebuild
-- that one with CREATE INDEX CONCURRENTLY in its own non-transactional migration instead.
-- IF NOT EXISTS keeps this migration idempotent.

-- notifications delivery scheduler (~10s): WHERE status=? ORDER BY created_at
CREATE INDEX IF NOT EXISTS idx_notifications_status_created_at
    ON notifications (status, created_at);

-- abandoned-booking reminder (~30m): WHERE status='EXPIRED' AND cancelled_at BETWEEN ? AND ?
-- (cancelled_at had no index at all before this).
CREATE INDEX IF NOT EXISTS idx_bookings_status_cancelled_at
    ON bookings (status, cancelled_at);

-- public experience page reviews: WHERE experience_id=? AND direction=? AND status=? ORDER BY created_at DESC
CREATE INDEX IF NOT EXISTS idx_reviews_experience_listing
    ON reviews (experience_id, direction, status, created_at DESC);

-- public host page reviews: WHERE local_profile_id=? AND direction=? AND status=? ORDER BY created_at DESC
CREATE INDEX IF NOT EXISTS idx_reviews_host_listing
    ON reviews (local_profile_id, direction, status, created_at DESC);

-- underbooked-slot cancellation sweep: WHERE start_time BETWEEN ? AND status=? ...
-- (the existing experience-leading composite can't serve this experience-less query).
CREATE INDEX IF NOT EXISTS idx_availability_status_start_time
    ON availability_slots (status, start_time);

-- wishlist reminder sweep (~30m): WHERE created_at BETWEEN ? AND ?
-- (the only wishlist index leads with user_id, unusable for a created_at-only range).
CREATE INDEX IF NOT EXISTS idx_wishlist_items_created_at
    ON wishlist_items (created_at);

-- conversation thread load: WHERE conversation_id=? ORDER BY created_at
CREATE INDEX IF NOT EXISTS idx_messages_conversation_created
    ON messages (conversation_id, created_at);
