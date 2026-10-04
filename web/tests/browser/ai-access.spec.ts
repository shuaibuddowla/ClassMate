import {test,expect} from '@playwright/test';
import {openSavedAccount} from './helpers/offline-fixtures';
test('AI navigation is removed and unauthorized profiles have no AI shortcut',async({page})=>{
 await openSavedAccount(page,1440);
 await expect(page.locator('.sidebar nav').getByRole('button',{name:'ClassMate AI',exact:true})).toHaveCount(0);
 await page.locator('.sidebar nav').getByRole('button',{name:'Profile',exact:true}).click();
 await expect(page.locator('.profile-screen').getByRole('button',{name:/ClassMate AI/})).toHaveCount(0);
 await page.setViewportSize({width:390,height:844});await expect(page.locator('.bottom-nav button')).toHaveCount(5);
 await expect(page.locator('.bottom-nav').getByRole('button',{name:'AI',exact:true})).toHaveCount(0);
 await page.evaluate(()=>{location.hash='ai';});await expect(page.locator('.profile-screen')).toBeVisible();await expect(page.locator('.ai-page')).toHaveCount(0);
});

test('an activated app update offers a refresh without interrupting the page',async({page})=>{
 await page.addInitScript(()=>{
   const workers = new EventTarget();
   Object.assign(workers, {
     controller: {},
     register: async()=>({update:async()=>{}}),
   });
   Object.defineProperty(navigator,'serviceWorker',{value:workers,configurable:true});
 });
 await openSavedAccount(page,390);
 await expect(page.getByRole('button',{name:'Refresh app',exact:true})).toHaveCount(0);
 await page.evaluate(()=>navigator.serviceWorker.dispatchEvent(new Event('controllerchange')));
 await expect(page.getByRole('button',{name:'Refresh app',exact:true})).toBeVisible();
 await expect(page.locator('.schedule-row')).toBeVisible();
 await page.getByRole('button',{name:'Refresh app',exact:true}).click();
 await expect(page.locator('.bottom-nav button')).toHaveCount(5);
 await expect(page.getByRole('button',{name:'Refresh app',exact:true})).toHaveCount(0);
});
