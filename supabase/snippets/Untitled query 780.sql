delete from device_alert
where created_at < now() - interval '30 days';