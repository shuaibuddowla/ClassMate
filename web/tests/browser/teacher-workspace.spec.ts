import {test,expect} from "@playwright/test";
import {openSavedAccount} from "./helpers/offline-fixtures";

test("teacher chooses an assigned batch and sees familiar scoped teaching tools",async({page})=>{
  await openSavedAccount(page,390);
  await page.evaluate(()=>{
    const p=JSON.parse(localStorage.getItem("classmate:identity:mobile-layout-test")!);
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
    if(name==="batch_friends")data=[{profile_id:"teacher-peer",role:"teacher",full_name:"Hidden teacher"},{profile_id:"student-one",role:"student",full_name:"Visible student",student_id:"CE25001"}];
    if(name==="unread_activity")data={notices:0,blood_requests:0};
    await route.fulfill({status:200,contentType:"application/json",body:JSON.stringify(data)});
  });
  await page.evaluate(()=>{Object.defineProperty(navigator,"onLine",{get:()=>true,configurable:true});window.dispatchEvent(new Event("online"));});
  await expect(page.getByRole("heading",{name:"Choose your classroom."})).toBeVisible();
  await expect(page.locator(".teacher-batch-list button")).toHaveCount(1);
  await page.locator(".teacher-batch-list button").click();
  await expect(page.locator(".bottom-nav").getByRole("button",{name:"Students",exact:true})).toBeVisible();
  await page.locator(".bottom-nav").getByRole("button",{name:"Students",exact:true}).click();
  await expect(page.getByRole("heading",{name:"Students",exact:true})).toBeVisible();
  await expect(page.locator(".people-grid")).toContainText("Visible student");
  await expect(page.locator(".people-grid")).not.toContainText("Hidden teacher");
  await page.locator(".bottom-nav").getByRole("button",{name:"Profile",exact:true}).click();
  await page.getByRole("button",{name:/Teaching tools/}).click();
  await expect(page.getByRole("heading",{name:"Teaching tools"})).toBeVisible();
  await expect(page.getByRole("button",{name:"Add course",exact:true})).toHaveCount(0);
  await expect(page.getByRole("button",{name:"People & approvals",exact:true})).toHaveCount(0);
  await page.screenshot({path:"../build/teacher-tools-mobile.png"});
});
