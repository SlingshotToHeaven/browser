-- Trace Processor SQL for a single settled edit on the candidate. Counts only:
-- matching timelines establish capture readiness, not which pixels were shown.
WITH browser AS (
  SELECT upid, pid FROM process WHERE name = 'com.browser'
), work AS (
  SELECT s.id, s.ts, s.dur, s.name, t.upid, t.utid,
    CASE WHEN t.tid = b.pid THEN 'main'
         WHEN t.name = 'Browser-omnibox' THEN 'worker' ELSE 'other' END AS lane
  FROM slice AS s
  JOIN thread_track AS tt ON tt.id = s.track_id
  JOIN thread AS t ON t.utid = tt.utid
  JOIN browser AS b ON b.upid = t.upid
  WHERE s.dur >= 0 AND (s.name GLOB 'Browser.omnibox.*' OR s.name GLOB 'deliverInputEvent*')
), edits AS (
  SELECT * FROM work WHERE name = 'Browser.omnibox.edit' AND lane = 'main'
), dispatched_edits AS (
  SELECT e.* FROM edits AS e
  WHERE EXISTS (
    SELECT 1 FROM work AS i
    WHERE i.utid = e.utid AND i.name GLOB 'deliverInputEvent*'
      AND e.ts >= i.ts AND e.ts + e.dur <= i.ts + i.dur
  )
), app_surface AS (
  SELECT a.* FROM actual_frame_timeline_slice AS a JOIN browser AS b USING (upid)
  WHERE a.dur > 0 AND a.surface_frame_token > 0
), paired_surface AS (
  SELECT a.id FROM app_surface AS a
  WHERE a.display_frame_token > 0 AND EXISTS (
    SELECT 1 FROM dispatched_edits AS e WHERE e.upid = a.upid AND a.ts + a.dur >= e.ts + e.dur
  ) AND EXISTS (
    SELECT 1 FROM actual_frame_timeline_slice AS d JOIN process AS p ON p.upid = d.upid
    WHERE d.display_frame_token = a.display_frame_token
      AND (p.name = 'surfaceflinger' OR p.name GLOB '*/surfaceflinger')
      AND COALESCE(d.surface_frame_token, 0) = 0 AND d.dur > 0 AND d.ts + d.dur >= a.ts + a.dur
  )
)
SELECT
  (SELECT count(*) FROM browser) AS browser_processes,
  (SELECT count(*) FROM work WHERE name GLOB 'deliverInputEvent*' AND lane = 'main') AS main_dispatches,
  (SELECT count(*) FROM edits) AS main_edits,
  (SELECT count(*) FROM dispatched_edits) AS dispatched_edits,
  (SELECT count(*) FROM work WHERE name = 'Browser.omnibox.lookup' AND lane = 'worker') AS worker_lookups,
  (SELECT count(*) FROM work WHERE name = 'Browser.omnibox.lookup' AND lane <> 'worker') AS other_lookups,
  (SELECT count(*) FROM work WHERE name = 'Browser.omnibox.localRows' AND lane = 'main') AS main_local_rows_slices,
  (SELECT count(*) FROM work WHERE name = 'Browser.omnibox.clipboard') AS clipboard_reads,
  (SELECT count(*) FROM app_surface) AS app_actual_surface_slices,
  (SELECT count(*) FROM paired_surface) AS post_edit_surface_display_pairs,
  (SELECT count(*) FROM stats WHERE severity = 'data_loss' AND value > 0) AS nonzero_data_loss_counters;
