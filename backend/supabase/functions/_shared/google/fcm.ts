// Push notifications through FCM HTTP v1. Optional: when no Firebase
// service account is configured, push is skipped and the in-app
// notification list still works.
import type { SupabaseClient } from "npm:@supabase/supabase-js@2.119.0";
import { accessToken, parseServiceAccount } from "./auth.ts";

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

export interface PushMessage {
  title: string;
  body: string;
  data?: Record<string, string>;
}

export function fcmConfigured(): boolean {
  return parseServiceAccount(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON"))?.project_id !== undefined;
}

/** Sends to every device of the user; drops tokens FCM reports as dead. Never throws. */
export async function sendPush(db: SupabaseClient, userId: string, msg: PushMessage): Promise<number> {
  const sa = parseServiceAccount(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON"));
  if (!sa?.project_id) return 0;
  try {
    const { data: devices } = await db.from("device_tokens").select("token").eq("user_id", userId).limit(10);
    if (!devices?.length) return 0;
    const bearer = await accessToken(sa, SCOPE);
    let sent = 0;
    for (const { token } of devices) {
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
        method: "POST",
        headers: { Authorization: `Bearer ${bearer}`, "content-type": "application/json" },
        body: JSON.stringify({
          message: {
            token,
            notification: { title: msg.title, body: msg.body },
            data: msg.data ?? {},
            android: { priority: "high", notification: { channel_id: "jobs" } },
          },
        }),
      });
      if (res.ok) {
        sent++;
      } else if (res.status === 404 || res.status === 400) {
        // UNREGISTERED / INVALID_ARGUMENT: the app was uninstalled or the token rotated.
        await db.from("device_tokens").delete().eq("token", token);
      } else {
        console.error("fcm error", res.status, (await res.text()).slice(0, 200));
      }
    }
    return sent;
  } catch (e) {
    console.error("push failed", e instanceof Error ? e.message : e);
    return 0;
  }
}
