import {test,expect} from "@playwright/test";
import {openSavedAccount} from "./helpers/offline-fixtures";

test("teacher chooses an assigned batch and sees familiar scoped teaching tools",async({page})=>{
  await openSavedAccount(page,390);
  await page.evaluate(()=>{
    const p=JSON.parse(localStorage.getItem("classmate:identity:mobile-layout-test")!);
    localStorage.removeItem("classmate:academic:v1:mobile-layout-test:layout-batch:routine");
    p.role="teacher";p.email="approved.teacher@gmail.com";p.batch_id=null;p.student_id=null;
    localStorage.setItem("classmate:identity:mobile-layout-test",JSON.stringify(p));
  });
  await page.reload();
  const course={id:"teacher-course",offering_id:"teacher-offering",course_code:"CSE2101",course_title:"Object Oriented Programming",course_type:"theory",teacher_name:"Fixture teacher"};
  await page.route("**/rest/v1/rpc/**",async route=>{
    const name=route.request().url().split("/").at(-1);
    let data:unknown=[];
    if(name==="available_batches")data=[{id:"layout-batch",department_id:"layout-department",batch_number:22,academic_session:25,departments:{code:"cse",name:"CSE"}}];
    if(name==="batch_course_catalog")data=[course];
    if(name==="batch_timetable_catalog")data=[course,{...course,offering_id:"other-offering",course_title:"Another teacher's course"}];
    if(name==="batch_friends")data=[{profile_id:"owner",role:"admin",full_name:"Campus owner"},{profile_id:"teacher-peer",role:"teacher",full_name:"Hidden teacher"},{profile_id:"student-one",role:"student",full_name:"Visible student",student_id:"CE25001"}];
    if(name==="unread_activity")data={notices:9,blood_requests:4};
    await route.fulfill({status:200,contentType:"application/json",body:JSON.stringify(data)});
  });
  let authorFiltered=false;
  await page.route("**/rest/v1/notices?**", async route=>{
    authorFiltered=new URL(route.request().url()).searchParams.get("author_id")==="eq.mobile-layout-test";
    await route.fulfill({status:200,contentType:"application/json",body:"[]"});
  });
  await page.evaluate(()=>{Object.defineProperty(navigator,"onLine",{get:()=>true,configurable:true});window.dispatchEvent(new Event("online"));});
  await expect(page.getByRole("heading",{name:"Choose your classroom."})).toBeVisible();
  await expect(page.locator(".teacher-batch-list button")).toHaveCount(1);
  await page.locator(".teacher-batch-list button").click();
  await expect(page.getByText(/You have no classes to take/)).toBeVisible();
  await page.locator(".bottom-nav").getByRole("button",{name:"Notices",exact:true}).click();
  await expect(page.getByText(/You haven.t posted any notices/)).toBeVisible();
  await page.getByRole("button",{name:/Pull to refresh/}).click();
  await expect.poll(()=>authorFiltered).toBe(true);
  await expect(page.locator(".bottom-nav").getByRole("button",{name:"Students",exact:true})).toBeVisible();
  await page.locator(".bottom-nav").getByRole("button",{name:"Students",exact:true}).click();
  await expect(page.getByRole("heading",{name:"Students",exact:true})).toBeVisible();
  await expect(page.locator(".people-grid")).toContainText("Visible student");
  await expect(page.locator(".people-grid")).not.toContainText("Hidden teacher");
  await expect(page.locator(".people-grid .person").first()).toContainText("Campus owner");
  await expect(page.locator(".people-grid .person").first()).toContainText("Admin");
  await expect(page.locator(".unread-badge")).toHaveCount(0);
  await page.locator(".bottom-nav").getByRole("button",{name:"Profile",exact:true}).click();
  await expect(page.getByText("Switch batch",{exact:true})).toBeVisible();
  await expect(page.getByRole("switch",{name:"Push notifications"})).toHaveCount(0);
  await page.getByRole("button",{name:/Teaching tools/}).click();
  await expect(page.getByRole("heading",{name:"Teaching tools"})).toBeVisible();
  await expect(page.getByRole("button",{name:"Add course",exact:true})).toHaveCount(0);
  await expect(page.getByRole("button",{name:"People & approvals",exact:true})).toHaveCount(0);
  await page.screenshot({path:"../build/teacher-tools-mobile.png"});
});
