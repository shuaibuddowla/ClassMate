import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("sign-in has two blue buttons and a desktop-specific composition", async ({page}) => {
  await page.route("**/*.supabase.co/**", r => r.fulfill({status:200,contentType:"application/json",body:"{}"}));
  await page.setViewportSize({width:1440,height:1000});
  await page.goto("/");
  const student=page.getByRole("button",{name:/Student sign-in/});
  const teacher=page.getByRole("button",{name:/Teacher sign-in/});
  await expect(student).toBeVisible(); await expect(teacher).toBeVisible();
  await expect(page.locator(".signin-story")).toBeVisible();
  expect(await student.evaluate(e=>getComputedStyle(e).backgroundColor)).toEqual(await teacher.evaluate(e=>getComputedStyle(e).backgroundColor));
  await page.screenshot({path:"../build/signin-desktop-new.png"});
  await page.setViewportSize({width:390,height:844});
  await expect(page.locator(".signin-story")).toBeHidden();
  await expect(teacher).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({path:"../build/signin-mobile-new.png"});
});

test("course files occupy a separate central page and browser back restores Library", async ({page}) => {
  await openSavedAccount(page,1280);
  const id="b2000000-0000-4000-8000-000000000006";
  await page.evaluate(id=> {
    const key="classmate:academic:v1:mobile-layout-test:layout-batch:courses";
    localStorage.setItem(key,JSON.stringify({data:[{offering_id:id,course_title:"Course with resources",course_code:"CSE2101",course_type:"theory"}],syncedAt:Date.now()}));
  },id);
  await page.reload();
  await page.route("**/rest/v1/file_metadata?**", async route => {
    const query=new URL(route.request().url()).searchParams;
    expect(query.get("batch_id")).toBe("eq.layout-batch");
    if(query.has("semester_course_id")) expect(query.get("semester_course_id")).toBe(`eq.${id}`);
    await route.fulfill({status:200,contentType:"application/json",body:JSON.stringify([{id:"resource",title:"Course syllabus",category:"syllabus",semester_course_id:id,size_bytes:1024}])});
  });
  await page.evaluate(()=>{Object.defineProperty(navigator,"onLine",{get:()=>true,configurable:true});window.dispatchEvent(new Event("online"));location.hash="library";});
  await page.getByRole("button",{name:/Course with resources/}).click();
  await expect(page.locator(".course-files-page")).toBeVisible();
  await expect(page.locator(".course-files-page .library-courses")).toHaveCount(0);
  await expect(page.locator(".course-files-page .file-list")).toContainText("Course syllabus");
  await expect(page.locator(".category-list .selected")).toHaveText("all");
  await page.goBack();
  await expect(page.locator(".library-courses")).toBeVisible();
  await expect(page.locator(".course-files-page")).toHaveCount(0);
});
