import {test,expect} from '@playwright/test';
import {openSavedAccount} from './helpers/offline-fixtures';
test.use({viewport:{width:393,height:851},isMobile:true,hasTouch:true,userAgent:'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'});
test('Android retains a native install prompt that arrived before hydration',async({page})=>{
 await page.addInitScript(()=>{
  const event=new Event('beforeinstallprompt');Object.assign(event,{prompt:async()=>{(window as any).installRequested=true;},userChoice:Promise.resolve({outcome:'accepted'})});(window as any).classmateInstallPrompt=event;
 });
 await page.goto('/');await page.getByRole('button',{name:'Install web app',exact:true}).click();
 expect(await page.evaluate(()=>(window as any).installRequested)).toBeTruthy();await expect(page.getByRole('dialog')).toHaveCount(0);
});
test('Android instructions can launch a prompt that becomes available later',async({page})=>{
 await page.goto('/');await page.getByRole('button',{name:'Install web app',exact:true}).click();await expect(page.getByRole('dialog')).toContainText('Add to Home screen');
 await page.evaluate(()=>{const event=new Event('beforeinstallprompt');Object.assign(event,{prompt:async()=>{(window as any).installRequested=true;},userChoice:Promise.resolve({outcome:'accepted'})});window.dispatchEvent(event);});
 await page.getByRole('button',{name:'Install now',exact:true}).click();expect(await page.evaluate(()=>(window as any).installRequested)).toBeTruthy();await expect(page.getByRole('dialog')).toHaveCount(0);
});
test('desktop install has the same outline as the Android download action',async({page})=>{
 await openSavedAccount(page,1440);
 const styles=await page.evaluate(()=>['.sidebar-android','.sidebar>.install-web-app'].map(selector=>{const s=getComputedStyle(document.querySelector(selector)!);return {border:s.border,borderRadius:s.borderRadius};}));
 expect(styles[1]).toEqual(styles[0]);
});
