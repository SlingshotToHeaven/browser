-- Optional setup-notice breakdown for Trace Processor v59.1.
-- Classify known prefixes locally; never return any message text or subjects.
WITH notices(kind) AS (
  SELECT CASE
    WHEN a.string_value IN ('Ftrace event failed: ftrace/print', 'Ftrace event unknown: ftrace/print')
      THEN 'ftrace_print_setup'
    WHEN a.string_value GLOB 'Ftrace event failed: *' THEN 'ftrace_event_failed'
    WHEN a.string_value GLOB 'Ftrace event unknown: *' THEN 'ftrace_event_unknown'
    WHEN a.string_value GLOB 'Atrace failures: *' THEN 'atrace_failure'
    WHEN a.string_value GLOB 'Ftrace exclusive feature error: *' THEN 'ftrace_exclusive_feature'
    ELSE 'other_or_missing_detail'
  END
  FROM __intrinsic_trace_import_logs AS l
  LEFT JOIN args AS a ON a.arg_set_id = l.arg_set_id AND a.key = 'message'
  WHERE l.stat_key IN (SELECT key FROM __intrinsic_stats WHERE name = 'ftrace_setup_errors')
)
SELECT kind, count(*) AS notices FROM notices GROUP BY kind ORDER BY kind;
