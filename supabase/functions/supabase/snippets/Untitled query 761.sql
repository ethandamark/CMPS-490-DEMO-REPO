insert into device_alert (device_id, alert_id, delivery_status)
select device_id, '123e4567-e89b-12d3-a456-426614174000', 'pending'
from device
where state_code = 'LA';