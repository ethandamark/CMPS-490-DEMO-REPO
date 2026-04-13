for (const device of devices) {
  const result = await fetch("https://fcm.googleapis.com/fcm/send", {
    method: "POST",
    headers: {
      "Authorization": `key=${Deno.env.get("BAXZi-TzIoVs2j-DqwBIqoVfeqXQMCxo3MGZ5lhSY5XpIlaiDjglHlcZ71WCBTeDbN1vcAQUdtFuYboG-LzCkcw")}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      to: device.fcm_token,
      notification: {
        title: props.event,
        body: props.headline
      }
    })
  });

  const status = result.ok ? "sent" : "failed";

  await fetch(`http://localhost:54321/rest/v1/device_alert`, {
    method: "PATCH",
    headers,
    body: JSON.stringify({
      delivery_status: status,
      sent_at: new Date().toISOString()
    })
  });
}