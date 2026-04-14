Deno.serve(async () => {
  try {
    const FCM_KEY = Deno.env.get("BAXZi-TzIoVs2j-DqwBIqoVfeqXQMCxo3MGZ5lhSY5XpIlaiDjglHlcZ71WCBTeDbN1vcAQUdtFuYboG-LzCkcw")!;

    // 🔥 Replace this with your actual device token
    const TEST_TOKEN = "eaAIuFD3S4a6Aw6H-V6OB9:APA91bE8u0fkIYQ9vljTvok4edx-VJPe3VCvRq6I6MpfMoxHNCZqx36fBhP1-1LnF0rtl93evAYtLmKVMIjG_TUbX1qv5OylszA7JnbSeZLiMRME7UxZmz0";

    const response = await fetch(
      "https://fcm.googleapis.com/fcm/send",
      {
        method: "POST",
        headers: {
          "Authorization": `key=${FCM_KEY}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          to: TEST_TOKEN,
          notification: {
            title: "Test Alert 🚨",
            body: "If you see this, Firebase works!"
          }
        })
      }
    );

    const result = await response.text();
    console.log("FCM test smiles response:", result);

    return new Response(result);

  } catch (err) {
    console.error(err);
    return new Response("Error", { status: 500 });
  }
});