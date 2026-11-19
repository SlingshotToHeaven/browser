-- Offline diagnostics. These counts are independent of the strict readiness joins.
-- Only aggregates and Perfetto's stat keys are returned, never raw trace values.
WITH slice_labels(name, pattern) AS (
  VALUES ('dispatch', 'deliverInputEvent*'),
         ('omnibox_edit', 'Browser.omnibox.edit'),
         ('omnibox_lookup', 'Browser.omnibox.lookup'),
         ('omnibox_local_rows', 'Browser.omnibox.localRows'),
         ('omnibox_clipboard', 'Browser.omnibox.clipboard')
), slice_counts(name, total, complete) AS (
  SELECT l.name, count(s.id), count(CASE WHEN s.dur >= 0 THEN s.id END)
  FROM slice_labels AS l LEFT JOIN slice AS s ON s.name GLOB l.pattern
  GROUP BY l.name
), observations(section, name, idx, value) AS (
  SELECT 'config', 'embedded_config_rows', NULL, count(*) FROM metadata
    WHERE name = 'trace_config_pbtxt' AND length(str_value) > 0
  UNION ALL SELECT 'config', 'retry_session_matches', NULL, count(*) FROM metadata
    WHERE name = 'unique_session_name' AND str_value = 'browser-omnibox-readiness-retry'
  UNION ALL SELECT 'process', 'process_rows', NULL, count(*) FROM process
  UNION ALL SELECT 'process', 'named_processes', NULL, count(*) FROM process WHERE length(name) > 0
  UNION ALL SELECT 'process', 'processes_with_cmdline', NULL, count(*) FROM process WHERE length(cmdline) > 0
  UNION ALL SELECT 'process', 'browser_named_processes', NULL, count(*) FROM process WHERE name = 'com.browser'
  UNION ALL SELECT 'process', 'browser_cmdline_matches', NULL, count(*) FROM process WHERE cmdline = 'com.browser'
  UNION ALL SELECT 'process', 'surfaceflinger_named_processes', NULL, count(*) FROM process
    WHERE name = 'surfaceflinger' OR name GLOB '*/surfaceflinger'
  UNION ALL SELECT 'process', 'thread_rows', NULL, count(*) FROM thread
  UNION ALL SELECT 'process', 'named_threads', NULL, count(*) FROM thread WHERE length(name) > 0
  UNION ALL SELECT 'process', 'omnibox_named_threads', NULL, count(*) FROM thread WHERE name = 'Browser-omnibox'
  UNION ALL SELECT 'ftrace', 'raw_event_rows', NULL, count(*) FROM ftrace_event
  UNION ALL SELECT 'ftrace', 'raw_print_event_rows', NULL, count(*) FROM ftrace_event WHERE name = 'print'
  UNION ALL SELECT 'slice_total', name, NULL, total FROM slice_counts
  UNION ALL SELECT 'slice_complete', name, NULL, complete FROM slice_counts
  UNION ALL SELECT 'frame', 'actual_rows', NULL, count(*) FROM actual_frame_timeline_slice
  UNION ALL SELECT 'frame', 'completed_surface_rows', NULL, count(*) FROM actual_frame_timeline_slice
    WHERE dur > 0 AND surface_frame_token > 0
  UNION ALL SELECT 'frame', 'completed_display_rows', NULL, count(*) FROM actual_frame_timeline_slice
    WHERE dur > 0 AND COALESCE(surface_frame_token, 0) = 0 AND display_frame_token > 0
  UNION ALL SELECT 'frame', 'browser_layer_label_surface_rows', NULL, count(*) FROM actual_frame_timeline_slice
    WHERE surface_frame_token > 0 AND layer_name GLOB '*com.browser/*'
), selected_stats AS (
  -- Keep zero-valued rows if returned by Perfetto; do not fill in absent rows.
  SELECT 'stat' AS section, name, idx, value FROM stats
  WHERE name IN (
    'traced_buf_buffer_size', 'traced_buf_bytes_written', 'traced_buf_bytes_overwritten',
    'traced_buf_chunks_discarded', 'traced_buf_chunks_overwritten', 'traced_buf_write_wrap_count',
    'traced_chunks_discarded', 'traced_patches_discarded', 'traced_flushes_failed',
    'traced_final_flush_failed', 'ftrace_setup_errors', 'ftrace_cpu_has_data_loss',
    'ftrace_cpu_read_events_begin', 'ftrace_cpu_read_events_end', 'ftrace_cpu_read_events_delta',
    'ftrace_cpu_overrun_delta', 'ftrace_cpu_dropped_events_delta', 'ftrace_cpu_commit_overrun_delta',
    'ftrace_packet_before_tracing_start', 'frame_timeline_event_parser_errors', 'frame_timeline_unpaired_end_event'
  ) OR (severity IN ('data_loss', 'error') AND value <> 0)
)
SELECT section, name, idx, value FROM observations
UNION ALL
SELECT section, name, idx, value FROM selected_stats
ORDER BY section, name, idx;
