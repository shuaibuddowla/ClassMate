import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { StreamChat } from "npm:stream-chat@8.40.0";
import { withWebCors } from "../_shared/web-cors.ts";

Deno.serve(request => withWebCors(request, async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SB_PUBLISHABLE_KEY") || Deno.env.get("SUPABASE_ANON_KEY") || "";
  const serviceKey = Deno.env.get("SB_SECRET_KEY") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  const streamKey = Deno.env.get("STREAM_API_KEY") ?? "";
  const streamSecret = Deno.env.get("STREAM_API_SECRET") ?? "";

  if (!url || !serviceKey) {
    return new Response(JSON.stringify({ error: "Server configuration missing" }), {
      status: 503,
      headers: { "Content-Type": "application/json" }
    });
  }

  if (!streamKey || !streamSecret) {
    return new Response(JSON.stringify({ error: "Stream Chat configuration missing" }), {
      status: 503,
      headers: { "Content-Type": "application/json" }
    });
  }

  const rawAuth = request.headers.get("authorization") || (request.headers.get("apikey") ? `Bearer ${request.headers.get("apikey")}` : `Bearer ${anonKey}`);
  if (!rawAuth.startsWith("Bearer ")) {
    return new Response(JSON.stringify({ error: "Unauthorized" }), {
      status: 401,
      headers: { "Content-Type": "application/json" }
    });
  }

  const token = rawAuth.slice(7).trim();
  const supabase = createClient(url, anonKey, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false },
  });

  const { data: { user }, error: userError } = await supabase.auth.getUser(token);
  if (userError || !user) {
    return new Response(JSON.stringify({ error: "Invalid user session" }), {
      status: 401,
      headers: { "Content-Type": "application/json" }
    });
  }

  // Retrieve user's verified academic profile
  const adminClient = createClient(url, serviceKey, { auth: { persistSession: false } }).schema("classmate");
  const { data: profile, error: profileError } = await adminClient
    .from("profiles")
    .select("id, full_name, role, batch_id, student_id, is_cr, username")
    .eq("id", user.id)
    .single();

  if (profileError || !profile) {
    return new Response(JSON.stringify({ error: "Student profile not found" }), {
      status: 404,
      headers: { "Content-Type": "application/json" }
    });
  }

  const studentName = profile.full_name || "ClassMate Student";
  const batchId = profile.batch_id || "";
  const currentUsername = profile.username || "";

  // Parse request body for optional action commands
  let reqBody: Record<string, any> = {};
  try {
    const text = await request.text();
    if (text.trim().length > 0) {
      reqBody = JSON.parse(text);
    }
  } catch (_e) {
    reqBody = {};
  }

  const serverClient = StreamChat.getInstance(streamKey, streamSecret);

  // --- ACTION: check_username ---
  if (reqBody.action === "check_username") {
    const requested = (reqBody.username || "").trim().toLowerCase().replace(/^@+/, "");
    const regex = /^[a-z0-9_]{3,20}$/;
    if (!regex.test(requested)) {
      return new Response(JSON.stringify({
        available: false,
        message: "3-20 characters, lowercase letters, numbers & underscores only"
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    const { data: existing } = await adminClient
      .from("profiles")
      .select("id")
      .ilike("username", requested)
      .maybeSingle();

    if (existing && existing.id !== user.id) {
      return new Response(JSON.stringify({
        available: false,
        message: `@${requested} is already taken`
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    return new Response(JSON.stringify({
      available: true,
      message: `@${requested} is available!`
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  }

  // --- ACTION: set_username ---
  if (reqBody.action === "set_username") {
    const requested = (reqBody.username || "").trim().toLowerCase().replace(/^@+/, "");
    const regex = /^[a-z0-9_]{3,20}$/;
    if (!regex.test(requested)) {
      return new Response(JSON.stringify({ error: "Invalid username format. 3-20 alphanumeric/underscore characters." }), {
        status: 400,
        headers: { "Content-Type": "application/json" }
      });
    }

    // Check collision
    const { data: existing } = await adminClient
      .from("profiles")
      .select("id")
      .ilike("username", requested)
      .maybeSingle();

    if (existing && existing.id !== user.id) {
      return new Response(JSON.stringify({ error: "Username is already claimed by another student" }), {
        status: 409,
        headers: { "Content-Type": "application/json" }
      });
    }

    // Update in Supabase
    const { error: updateErr } = await adminClient
      .from("profiles")
      .update({ username: requested })
      .eq("id", user.id);

    if (updateErr) {
      return new Response(JSON.stringify({ error: updateErr.message }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      });
    }

    // Sync to Stream Chat user metadata
    try {
      await serverClient.partialUpdateUser({
        id: user.id,
        set: {
          username: requested,
          name: studentName
        }
      });
    } catch (streamErr) {
      console.error("Stream update username error:", streamErr);
    }

    return new Response(JSON.stringify({
      success: true,
      username: requested
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  }

  // --- ACTION: search_users (University-wide ecosystem search) ---
  if (reqBody.action === "search_users") {
    const query = (reqBody.query || "").trim().toLowerCase().replace(/^@+/, "");
    if (query.length < 2) {
      return new Response(JSON.stringify({ users: [] }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    // Search by username or full name across all active profiles with a username
    const { data: matches, error: searchErr } = await adminClient
      .from("profiles")
      .select("id, full_name, role, batch_id, student_id, is_cr, username")
      .or(`username.ilike.%${query}%,full_name.ilike.%${query}%`)
      .not("username", "is", null)
      .neq("id", user.id)
      .limit(30);

    if (searchErr) {
      return new Response(JSON.stringify({ error: searchErr.message }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      });
    }

    // Upsert discovered users into Stream Chat
    try {
      const streamUsers = (matches || []).map((m: any) => ({
        id: m.id,
        name: m.full_name || "Classmate",
        role: "user",
        username: m.username || "",
        student_id: m.student_id || "",
        batch_id: m.batch_id || "",
        is_cr: m.is_cr || false
      }));
      if (streamUsers.length > 0) {
        await serverClient.upsertUsers(streamUsers);
      }
    } catch (_e) {}

    return new Response(JSON.stringify({
      users: (matches || []).map((m: any) => ({
        id: m.id,
        name: m.full_name || "Classmate",
        username: m.username || "",
        studentId: m.student_id || "",
        batchId: m.batch_id || "",
        isCr: m.is_cr || false,
        role: m.role || "student",
        avatarUrl: ""
      }))
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  }

  // --- ACTION: get_batchmates ---
  if (reqBody.action === "get_batchmates") {
    if (!batchId) {
      return new Response(JSON.stringify({ classmates: [] }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    const { data: classmates, error: cmError } = await adminClient
      .from("profiles")
      .select("id, full_name, role, batch_id, student_id, is_cr, username")
      .eq("batch_id", batchId)
      .neq("id", user.id)
      .order("is_cr", { ascending: false })
      .order("full_name", { ascending: true });

    if (cmError) {
      return new Response(JSON.stringify({ error: cmError.message }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      });
    }

    // Auto-upsert batchmates into Stream Chat so their avatars and profiles are instantly discoverable
    try {
      const streamUsers = (classmates || []).map((cm: any) => ({
        id: cm.id,
        name: cm.full_name || "Classmate",
        role: "user",
        image: "",
        username: cm.username || "",
        student_id: cm.student_id || "",
        batch_id: cm.batch_id || batchId,
        is_cr: cm.is_cr || false
      }));
      if (streamUsers.length > 0) {
        await serverClient.upsertUsers(streamUsers);
      }
    } catch (upsertErr) {
      console.error("Error bulk upserting batchmates to Stream:", upsertErr);
    }

    return new Response(JSON.stringify({
      batchId: batchId,
      classmates: (classmates || []).map((cm: any) => ({
        id: cm.id,
        name: cm.full_name || "Classmate",
        username: cm.username || "",
        studentId: cm.student_id || "",
        batchId: cm.batch_id || batchId,
        isCr: cm.is_cr || false,
        role: cm.role || "student",
        avatarUrl: ""
      }))
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  }

  // --- 1. Upsert Current User in Stream ---
  try {
    await serverClient.upsertUser({
      id: user.id,
      name: studentName,
      role: "user",
      username: currentUsername,
      student_id: profile.student_id || "",
      batch_id: batchId,
      is_cr: profile.is_cr || false
    });
  } catch (upsertErr) {
    console.error("Stream upsert user error:", upsertErr);
  }

  // --- 2. Generate scoped Stream user token ---
  const streamToken = serverClient.createToken(user.id);

  // --- 3. Provision Batch Discussion Group on server ---
  if (batchId) {
    try {
      const cleanBatch = batchId.replace(/-/g, "_");
      const channelId = `batch_${cleanBatch}`;
      const channel = serverClient.channel("messaging", channelId, {
        name: "Discussion Group",
        channel_type: "discussion_group",
        created_by_id: user.id
      });

      await channel.create();
      await channel.addMembers([user.id]);
      await channel.updatePartial({
        set: {
          name: "Discussion Group",
          channel_type: "discussion_group"
        }
      });

      const state = await channel.query({ messages: { limit: 1 } });
      if (!state.messages || state.messages.length === 0) {
        await channel.sendMessage({
          text: "👋 Welcome to your ClassMate Discussion Group! Feel free to discuss classes, assignments, and study materials here.",
          user_id: user.id
        });
      }
    } catch (chErr) {
      console.error("Stream provision channel error:", chErr);
    }
  }

  // --- 4. Normalize 1-on-1 direct message channels ---
  try {
    const userChannels = await serverClient.queryChannels({
      type: "messaging",
      members: { $in: [user.id] }
    });
    for (const c of userChannels) {
      if (!c.id.startsWith("batch_")) {
        const memberNames = Object.values(c.state?.members || {})
          .map((m: any) => `${m.user?.name || ""} ${m.user?.username || ""}`)
          .join(" ");
        await c.updatePartial({
          set: {
            channel_type: "direct_message",
            member_names: memberNames
          },
          unset: ["name"]
        });
      }
    }
  } catch (normErr) {
    console.error("Channel normalization error:", normErr);
  }

  return new Response(JSON.stringify({
    token: streamToken,
    apiKey: streamKey,
    userId: user.id,
    userName: studentName,
    username: currentUsername,
    studentId: profile.student_id || "",
    batchId: batchId,
    isCr: profile.is_cr || false,
    role: profile.role || "student"
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}));
