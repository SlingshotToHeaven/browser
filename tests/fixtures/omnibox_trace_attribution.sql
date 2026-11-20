-- Report-10 retained-trace probe. Diagnostic only; never substitutes for pilot.sql.
-- Return counts and offsets relative to one main edit, not event IDs or absolute times.
WITH RECURSIVE browser AS (
  SELECT upid, pid FROM process WHERE name = 'com.browser'
), main_work AS (
  SELECT s.id, s.name, s.ts, s.dur, t.upid
  FROM slice AS s
  JOIN thread_track AS tt ON tt.id = s.track_id
  JOIN thread AS t ON t.utid = tt.utid
  JOIN browser AS b ON b.upid = t.upid AND b.pid = t.tid
  WHERE s.dur >= 0 AND (s.name GLOB 'deliverInputEvent*' OR s.name IN (
    'Browser.omnibox.edit', 'Browser.omnibox.localRows'
  ))
), edits AS (
  SELECT * FROM main_work WHERE name = 'Browser.omnibox.edit'
), one_edit AS (
  SELECT * FROM edits WHERE (SELECT count(*) FROM edits) = 1
), dispatches AS (
  SELECT * FROM main_work WHERE name GLOB 'deliverInputEvent*'
), relative_dispatches AS (
  SELECT d.*, e.ts AS edit_ts, e.ts + e.dur AS edit_end,
    row_number() OVER (ORDER BY d.ts, d.id) AS ordinal
  FROM dispatches AS d JOIN one_edit AS e USING (upid)
), async_windows AS (
  SELECT s.id, pt.upid, s.ts, s.dur, a.int_value AS cookie
  FROM slice AS s
  JOIN process_track AS pt ON pt.id = s.track_id
  JOIN browser AS b ON b.upid = pt.upid
  LEFT JOIN args AS a ON a.arg_set_id = s.arg_set_id AND a.key = 'cookie'
  WHERE pt.type = 'atrace_async_slice' AND s.name = 'deliverInputEvent' AND s.dur >= 0
), matched_windows AS (
  -- ViewRootImpl uses the same int32 InputEvent ID in its sync marker and async cookie.
  -- This joins *two framework markers*. It does not link either marker to the app edit.
  SELECT d.ordinal, w.id, w.ts, w.dur
  FROM relative_dispatches AS d JOIN async_windows AS w USING (upid)
  WHERE w.cookie IS NOT NULL AND w.ts <= d.ts AND w.ts + w.dur >= d.ts
    AND d.name GLOB ('deliverInputEvent src=0x* eventTimeNano=* id=0x' || printf('%x', w.cookie & 4294967295))
), input_flow_sources(id, role) AS (
  SELECT id, 'dispatch' FROM relative_dispatches
  UNION SELECT id, 'async_window' FROM matched_windows
), edit_scope(id) AS (
  SELECT id FROM one_edit
  UNION
  SELECT s.parent_id FROM slice AS s JOIN edit_scope AS p ON p.id = s.id WHERE s.parent_id IS NOT NULL
), flow_to_edit_scope AS (
  SELECT s.role AS source_role,
    CASE WHEN f.slice_in IN (SELECT id FROM one_edit) THEN 'edit' ELSE 'ancestor' END AS target_role
  FROM flow AS f JOIN input_flow_sources AS s ON s.id = f.slice_out
  WHERE f.slice_in IN (SELECT id FROM edit_scope)
), after_edit_surfaces AS (
  SELECT a.* FROM actual_frame_timeline_slice AS a JOIN one_edit AS e USING (upid)
  WHERE a.dur > 0 AND a.surface_frame_token > 0 AND a.ts + a.dur >= e.ts + e.dur
), after_edit_pairs AS (
  SELECT a.* FROM after_edit_surfaces AS a
  WHERE a.display_frame_token > 0 AND EXISTS (
    SELECT 1 FROM actual_frame_timeline_slice AS d JOIN process AS p ON p.upid = d.upid
    WHERE d.display_frame_token = a.display_frame_token
      AND (p.name = 'surfaceflinger' OR p.name GLOB '*/surfaceflinger')
      AND COALESCE(d.surface_frame_token, 0) = 0 AND d.dur > 0 AND d.ts + d.dur >= a.ts + a.dur
  )
), result(section, name, idx, value) AS (
  SELECT 'shape', 'main_edit_rows', NULL, count(*) FROM edits
  UNION ALL SELECT 'shape', 'main_dispatch_rows', NULL, count(*) FROM dispatches
  UNION ALL SELECT 'shape', 'single_edit_anchor', NULL, count(*) FROM one_edit
  UNION ALL SELECT 'shape', 'async_windows', NULL, count(DISTINCT id) FROM async_windows
  UNION ALL SELECT 'shape', 'async_windows_with_cookie', NULL, count(DISTINCT id) FROM async_windows WHERE cookie IS NOT NULL
  UNION ALL SELECT 'dispatch', 'start_offset_ns', ordinal, ts - edit_ts FROM relative_dispatches
  UNION ALL SELECT 'dispatch', 'end_offset_ns', ordinal, ts + dur - edit_ts FROM relative_dispatches
  UNION ALL SELECT 'dispatch', 'event_id_matched_windows', d.ordinal, (
    SELECT count(DISTINCT w.id) FROM matched_windows AS w WHERE w.ordinal = d.ordinal
  ) FROM relative_dispatches AS d
  UNION ALL SELECT 'dispatch', 'matched_windows_covering_edit', d.ordinal, (
    SELECT count(DISTINCT w.id) FROM matched_windows AS w
    WHERE w.ordinal = d.ordinal AND w.ts <= d.edit_ts AND w.ts + w.dur >= d.edit_end
  ) FROM relative_dispatches AS d
  UNION ALL SELECT 'flow', 'total_edges_in_trace', NULL, count(*) FROM flow
  UNION ALL SELECT 'flow', 'input_to_exact_edit_edges', NULL, count(*) FROM flow_to_edit_scope WHERE target_role = 'edit'
  UNION ALL SELECT 'flow', 'input_to_edit_or_ancestor_edges', NULL, count(*) FROM flow_to_edit_scope
  UNION ALL SELECT 'flow_roles', source_role || '_to_' || target_role, NULL, count(*) FROM flow_to_edit_scope
    GROUP BY source_role, target_role
  UNION ALL SELECT 'frame', 'surfaces_after_main_edit', NULL, count(*) FROM after_edit_surfaces
  UNION ALL SELECT 'frame', 'pairs_after_main_edit', NULL, count(*) FROM after_edit_pairs
  UNION ALL SELECT 'frame', 'pairs_after_later_local_rows', NULL, count(*) FROM after_edit_pairs AS a
    WHERE EXISTS (
      SELECT 1 FROM main_work AS l JOIN one_edit AS e USING (upid)
      WHERE l.name = 'Browser.omnibox.localRows' AND l.upid = a.upid
        AND l.ts >= e.ts + e.dur AND a.ts + a.dur >= l.ts + l.dur
    )
)
SELECT section, name, idx, value FROM result ORDER BY section, name, idx;
