interface Env {
  CLASSMATE_SUPABASE_URL: string;
  CLASSMATE_DISPATCH_SECRET: string;
}

export default {
  async fetch(): Promise<Response> {
    return new Response("Not found", { status: 404 });
  },
  async scheduled(_event: ScheduledController, env: Env): Promise<void> {
    if (!env.CLASSMATE_DISPATCH_SECRET) throw new Error("Dispatcher secret unavailable");
    const response = await fetch(
      `${env.CLASSMATE_SUPABASE_URL}/functions/v1/dispatch-classmate-notifications`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-dispatch-secret": env.CLASSMATE_DISPATCH_SECRET,
        },
        body: "{}",
      },
    );
    if (!response.ok) throw new Error(`ClassMate dispatcher returned ${response.status}`);
  },
} satisfies ExportedHandler<Env>;
