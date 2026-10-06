import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("unread badges show counts and Friends opens an unseen blood request once", async ({page}) => {
  await openSavedAccount(page,390);
  let read=false, marks=0;
  const request={id:"b1000000-0000-4000-8000-000000000021",blood_group:"A+",hospital:"Tangail General Hospital",units:2,needed_by:new Date(Date.now()+86400000).toISOString(),status:"open",patient_name:"Fixture patient",requester_name:"Fixture student",attendant_phone:"+8801700000099",can_manage:false,can_donate:false,volunteers:[]};
  await page.route("**/rest/v1/rpc/**", async route => {
    const name=route.request().url().split("/").at(-1);
    let data:unknown=[];
    if(name==="unread_activity") data={notices:12,blood_requests:read?0:1};
    if(name==="unread_blood_requests") data=read?[]:[request];
    if(name==="blood_request_details") data=request;
    if(name==="mark_blood_request_read") {read=true;marks++;data=null;}
    await route.fulfill({status:200,contentType:"application/json",body:JSON.stringify(data)});
  });
  await page.evaluate(()=>{Object.defineProperty(navigator,"onLine",{get:()=>true,configurable:true});window.dispatchEvent(new Event("online"));});
  const nav=page.locator(".bottom-nav");
  await expect(nav.getByRole("button",{name:/Notices/}).locator(".unread-badge")).toHaveText("12");
  await expect(nav.getByRole("button",{name:/Friends/}).locator(".unread-badge")).toHaveText("1");
  await nav.getByRole("button",{name:/Friends/}).click();
  await expect(page.getByRole("dialog")).toContainText("Fixture patient");
  await expect(page.getByRole("dialog")).toContainText("Tangail General Hospital");
  await expect(page.getByRole("link",{name:"Call attendant"})).toHaveAttribute("href","tel:+8801700000099");
  await expect(nav.getByRole("button",{name:/Friends/}).locator(".unread-badge")).toHaveCount(0);
  expect(marks).toBe(1);
  await page.screenshot({path:"../build/unread-blood-popup-mobile.png"});
  await page.getByRole("dialog").getByRole("button",{name:"Close",exact:true}).click();
  await nav.getByRole("button",{name:/Profile/}).click();
  await nav.getByRole("button",{name:/Friends/}).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button",{name:"Blood requests",exact:true})).toBeVisible();
  await page.screenshot({path:"../build/unread-friends-mobile.png"});
});
