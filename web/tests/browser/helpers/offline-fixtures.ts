import { expect } from "@playwright/test";
export async function openSavedAccount(page: any, width: number) {
  await page.setViewportSize({ width, height: 844 });
  await page.emulateMedia({ colorScheme: "light" });
  await page.route("**/*.supabase.co/**", (route: any) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "onLine", { get: () => false, configurable: true });
    if (localStorage.getItem("classmate:last-account")) return;
    const user = "mobile-layout-test",
      batch = "layout-batch";
    const date = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Dhaka",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date());
    const day = new Date(date + "T12:00:00").getDay();
    localStorage.setItem("classmate:last-account", user);
    localStorage.setItem(
      "classmate:identity:" + user,
      JSON.stringify({
        id: user,
        full_name: "A long student name for responsive layout",
        email: "student@mbstu.ac.bd",
        role: "student",
        verification_status: "active",
        department_id: "layout-department",
        batch_id: batch,
        student_id: "CS24001",
        profile_completed_at: date,
      }),
    );
    localStorage.setItem("classmate:theme", "system");
    const save = (scope: string, key: string, data: any) =>
      localStorage.setItem(
        `classmate:academic:v1:${user}:${scope}:${key}`,
        JSON.stringify({ data, syncedAt: Date.now() }),
      );
    const course = {
      offering_id: "layout-course",
      course_title: "Computer Organization and Architecture",
      course_type: "theory",
    };
    save("account", "batches", [
      {
        id: batch,
        departments: { code: "cse" },
        batch_number: 24,
        academic_session: 24,
      },
    ]);
    save(batch, "courses", [course]);
    const pages = [0, 1].map((page) => ({
      entries: Array.from({ length: 10 }, (_, i) => ({
        id: `offline-notice-${page * 10 + i}`,
        title: `Saved notice ${page * 10 + i + 1}`,
        body: "Last synced academic update",
        published_at: `2026-10-03T${String(23 - page * 10 - i).padStart(2, "0")}:00:00Z`,
        author_id: "offline-author",
      })),
      details: {},
      hasMore: true,
    }));
    save(batch, "notices", pages);
    save(batch, "calendar", []);
    save(batch, "buses", []);
    save(batch, "routine", [
      {
        id: "slot",
        semester_course_id: "layout-course",
        course,
        day_of_week: day,
        start_time: "09:00",
        end_time: "09:50",
        room: "301",
        type: "class",
      },
    ]);
    save(batch, `timetable-details:${date}:layout-course`, [
      { semester_course_id: "layout-course", teacher_name: "Assigned Teacher" },
    ]);
  });
  await page.goto("/");
  await expect(page.locator(".schedule-row")).toBeVisible();
}
