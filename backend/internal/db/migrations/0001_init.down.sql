-- Reverse of 0001_init.up.sql. Dropped in dependency order.

DROP TABLE IF EXISTS audit_log_entry;
DROP TABLE IF EXISTS autonomous_action;
DROP TABLE IF EXISTS autonomy_setting;
DROP TABLE IF EXISTS recommendation;
DROP TABLE IF EXISTS rca_verdict;
DROP TABLE IF EXISTS service_dependency;
DROP TABLE IF EXISTS alert;
DROP TABLE IF EXISTS incident;
DROP TABLE IF EXISTS evaluation_run_metric;
DROP TABLE IF EXISTS evaluation_run;
DROP TABLE IF EXISTS healing_event;
DROP TABLE IF EXISTS scaling_event;
DROP TABLE IF EXISTS error_budget_snapshot;
DROP TABLE IF EXISTS slo;
DROP TABLE IF EXISTS reliability_score_snapshot;
DROP TABLE IF EXISTS metric_sample;
DROP TABLE IF EXISTS endpoint;
DROP TABLE IF EXISTS deployment_target;
DROP TABLE IF EXISTS policy;
DROP TABLE IF EXISTS service;
DROP TABLE IF EXISTS cluster;
DROP TABLE IF EXISTS app_user;
DROP TABLE IF EXISTS organization;
